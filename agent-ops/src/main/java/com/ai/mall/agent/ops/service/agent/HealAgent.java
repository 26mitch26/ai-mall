package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.HealAction;
import com.ai.mall.agent.ops.model.HealLevel;
import com.ai.mall.agent.ops.model.RCAResult;
import com.ai.mall.agent.ops.service.event.EventBus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 自愈Agent：基于分级自愈(L0/L1/L2) + Playbook匹配的智能故障修复
 *
 * 分级自愈流程：
 * - L0_AUTO：自动执行，无需人工介入（高置信度 + 低爆炸半径）
 * - L1_ONCALL：值班工程师确认后执行（中等置信度）
 * - L2_APPROVAL：需要团队负责人审批（低置信度 + 高爆炸半径）
 *
 * Playbook：预定义的故障修复剧本，包含触发条件、执行步骤、验证方式，
 * 通过评分机制匹配最佳Playbook，确保修复方案的精准性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealAgent {

    private final EventBus eventBus;

    /**
     * 事件驱动入口：消费 aiops.events 上的根因结果，产出处置动作发布到 aiops.commands。
     * 与 RCAAgent 之间完全经由事件总线解耦。
     */
    @PostConstruct
    public void subscribeToRcaResults() {
        eventBus.subscribe(EventBus.AIOPS_EVENTS, RCAResult.class, this::heal);
    }

    /**
     * Playbook定义：预定义的故障修复剧本
     * 每个Playbook包含：名称、触发条件关键词、执行步骤、验证方式、适用自愈级别
     */
    private static final List<Playbook> PLAYBOOKS = List.of(
            Playbook.of("rollback",
                    List.of("deployment_regression", "config_error", "bad_deploy"),
                    List.of("1.识别最近部署版本", "2.回滚至上一稳定版本", "3.验证服务健康检查通过", "4.通知相关团队"),
                    "service_health_check_pass && error_rate < 0.01",
                    HealLevel.L0_AUTO),
            Playbook.of("restart",
                    List.of("memory_leak", "gc_overhead", "oom", "thread_deadlock"),
                    List.of("1.摘除服务流量(从注册中心摘除)", "2.等待在途请求完成", "3.重启服务实例", "4.验证服务就绪后恢复流量"),
                    "service_up && response_time_p99 < 500",
                    HealLevel.L0_AUTO),
            Playbook.of("scale_out",
                    List.of("traffic_spike", "resource_contention", "high_load", "cpu_saturation"),
                    List.of("1.评估当前负载与容量", "2.扩容服务实例数", "3.更新负载均衡配置", "4.验证流量均衡分发"),
                    "cpu_usage < 0.7 && request_success_rate > 0.99",
                    HealLevel.L1_ONCALL),
            Playbook.of("clear_cache",
                    List.of("cache_bloat", "cache_corruption", "stale_cache"),
                    List.of("1.标记缓存节点为维护状态", "2.清除本地缓存数据", "3.预热热点数据", "4.恢复缓存节点"),
                    "cache_hit_rate > 0.8 && memory_usage < 0.75",
                    HealLevel.L1_ONCALL),
            Playbook.of("disk_cleanup",
                    List.of("log_bloat", "disk_full", "data_growth"),
                    List.of("1.归档并压缩历史日志", "2.清理临时文件和过期数据", "3.扩容磁盘(如需要)", "4.验证磁盘使用率恢复安全水位"),
                    "disk_usage < 0.8",
                    HealLevel.L1_ONCALL),
            Playbook.of("network_repair",
                    List.of("dns_failure", "connection_pool_exhaustion", "firewall_misconfig"),
                    List.of("1.切换至备用DNS/连接池", "2.检查网络策略配置", "3.修复防火墙规则", "4.验证网络连通性"),
                    "network_latency < 100ms && connection_pool_active < 0.8",
                    HealLevel.L2_APPROVAL),
            Playbook.of("failover",
                    List.of("disk_failure", "hardware_failure", "data_corruption"),
                    List.of("1.标记故障节点为不可用", "2.切换流量至备用节点", "3.启动数据恢复流程", "4.验证数据一致性"),
                    "replication_lag < 5s && data_integrity_check_pass",
                    HealLevel.L2_APPROVAL)
    );

    public HealAction heal(RCAResult rcaResult) {
        log.info("HealAgent processing RCA result for alert: {}", rcaResult.getAlertId());

        // Step1: 匹配最佳Playbook（基于评分机制）
        PlaybookMatchResult matchResult = matchBestPlaybook(rcaResult);
        log.info("Playbook match: playbook={}, score={}", matchResult.playbook.name, formatDouble(matchResult.score));

        // Step2: 确定自愈级别
        HealLevel level = determineLevel(rcaResult, matchResult);

        // Step3: 计算爆炸半径
        double blastRadius = calculateBlastRadius(rcaResult);

        // Step4: 执行Dry-Run预演
        boolean dryRunPassed = dryRun(matchResult.playbook);

        // Step5: 构建自愈动作
        HealAction healAction = HealAction.builder()
                .id(UUID.randomUUID().toString())
                .alertId(rcaResult.getAlertId())
                .level(level)
                .action(matchResult.playbook.name)
                .playbook(matchResult.playbook.name)
                .blastRadius(blastRadius)
                .status(dryRunPassed ? "ready" : "dry_run_failed")
                .dryRunPassed(dryRunPassed)
                .build();

        eventBus.publish("aiops.commands", healAction);
        log.info("Heal action created: level={}, playbook={}, blastRadius={}, dryRun={}",
                level, matchResult.playbook.name, formatDouble(blastRadius), dryRunPassed);
        return healAction;
    }

    /**
     * 确定自愈级别：
     * - L0_AUTO：高置信度(>0.7) + Playbook评分高(>0.8) + 低爆炸半径(<0.3) → 自动执行，无需人工介入
     * - L1_ONCALL：中等置信度(0.4~0.7) → 值班工程师确认后执行
     * - L2_APPROVAL：低置信度(<0.4) 或 高爆炸半径(>=0.6) → 需要团队负责人审批
     */
    private HealLevel determineLevel(RCAResult rcaResult, PlaybookMatchResult matchResult) {
        double confidence = rcaResult.getConfidence();
        double blastRadius = calculateBlastRadius(rcaResult);

        // L0_AUTO: 自动执行，无需人工介入
        if (confidence > 0.7 && matchResult.score > 0.8 && blastRadius < 0.3) {
            return HealLevel.L0_AUTO;
        }
        // L2_APPROVAL: 需要团队负责人审批
        if (confidence < 0.4 || blastRadius >= 0.6) {
            return HealLevel.L2_APPROVAL;
        }
        // L1_ONCALL: 值班工程师确认后执行
        return HealLevel.L1_ONCALL;
    }

    /**
     * Playbook匹配评分：基于根因与Playbook触发条件的关键词匹配度计算评分
     *
     * 评分逻辑：
     * 1. 根因关键词与Playbook触发条件关键词的交集比例作为匹配分
     * 2. Playbook预定义级别与根因置信度的适配度作为加分项
     * 3. 选择评分最高的Playbook
     */
    private PlaybookMatchResult matchBestPlaybook(RCAResult rcaResult) {
        String rootCause = rcaResult.getRootCause().toLowerCase();
        List<String> suggestedActions = rcaResult.getSuggestedActions();

        double bestScore = 0.0;
        Playbook bestPlaybook = PLAYBOOKS.get(0);

        for (Playbook playbook : PLAYBOOKS) {
            double score = 0.0;

            // 基于根因关键词匹配评分
            for (String keyword : playbook.triggerKeywords) {
                if (rootCause.contains(keyword.toLowerCase())) {
                    score += 0.4;
                }
            }

            // 基于建议动作匹配评分
            for (String action : suggestedActions) {
                if (playbook.name.equalsIgnoreCase(action)) {
                    score += 0.3;
                }
            }

            // 置信度适配加分：高置信度场景优先匹配L0级别Playbook
            if (rcaResult.getConfidence() > 0.7 && playbook.level == HealLevel.L0_AUTO) {
                score += 0.2;
            } else if (rcaResult.getConfidence() > 0.4 && playbook.level == HealLevel.L1_ONCALL) {
                score += 0.1;
            }

            if (score > bestScore) {
                bestScore = score;
                bestPlaybook = playbook;
            }
        }

        return new PlaybookMatchResult(bestPlaybook, bestScore);
    }

    private double calculateBlastRadius(RCAResult rcaResult) {
        int chainSize = rcaResult.getImpactChain().size();
        return Math.min(1.0, chainSize * 0.15);
    }

    /**
     * Dry-Run 预演：检查 playbook 步骤是否可执行。
     *
     * <p>此前这里是 {@code for (step : steps) log.debug(...); return true;}——
     * 无论步骤是否为空都返回 true，使下游的 {@code dry_run_failed} 分支成为死代码。
     * 现在改为真实判定：至少要有一个非空步骤，且不允许出现占位步骤。
     */
    private boolean dryRun(Playbook playbook) {
        log.info("Executing dry-run for playbook: {} ({} steps)", playbook.name, playbook.steps.size());
        if (playbook.steps.isEmpty()) {
            log.warn("Dry-run rejected: playbook {} has no steps", playbook.name);
            return false;
        }
        for (String step : playbook.steps) {
            if (step == null || step.isBlank() || step.toLowerCase().contains("todo")) {
                log.warn("Dry-run rejected: playbook {} has a placeholder step: {}", playbook.name, step);
                return false;
            }
            log.debug("Dry-run step: {}", step);
        }
        log.info("Dry-run verification: {}", playbook.verification);
        return true;
    }

    /** 全部 playbook 名称（供前端与审批单展示可用处置动作）。 */
    public List<String> playbookNames() {
        return PLAYBOOKS.stream().map(playbook -> playbook.name).toList();
    }

    /**
     * 取某个 playbook 的执行步骤。
     *
     * <p>供 {@code SimulatedExecutor} 使用：此前 {@code steps} 只出现在 dryRun 的日志里，
     * 从未被任何代码消费，模拟执行器因此拿不到步骤内容。
     */
    public List<String> stepsOf(String playbookName) {
        if (playbookName == null) {
            return List.of();
        }
        return PLAYBOOKS.stream()
                .filter(playbook -> playbook.name.equalsIgnoreCase(playbookName))
                .findFirst()
                .map(playbook -> List.copyOf(playbook.steps))
                .orElseGet(List::of);
    }

    /** 取某个 playbook 声明的验证条件。 */
    public String verificationOf(String playbookName) {
        if (playbookName == null) {
            return "";
        }
        return PLAYBOOKS.stream()
                .filter(playbook -> playbook.name.equalsIgnoreCase(playbookName))
                .findFirst()
                .map(playbook -> playbook.verification)
                .orElse("");
    }

    /**
     * Playbook定义：包含触发条件、执行步骤、验证方式
     */
    private static class Playbook {
        final String name;
        final List<String> triggerKeywords;
        final List<String> steps;
        final String verification;
        final HealLevel level;

        private Playbook(String name, List<String> triggerKeywords, List<String> steps,
                         String verification, HealLevel level) {
            this.name = name;
            this.triggerKeywords = triggerKeywords;
            this.steps = steps;
            this.verification = verification;
            this.level = level;
        }

        static Playbook of(String name, List<String> triggerKeywords, List<String> steps,
                           String verification, HealLevel level) {
            return new Playbook(name, triggerKeywords, steps, verification, level);
        }
    }

    /** Playbook匹配结果 */
    private record PlaybookMatchResult(Playbook playbook, double score) {}

    /**
     * 格式化小数为固定两位小数字符串。
     * SLF4J 只识别 {} 占位符，不支持 Python 风格的 {:.2f}，需先自行格式化。
     */
    private static String formatDouble(double value) {
        return String.format("%.2f", value);
    }
}
