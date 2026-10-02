package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.ChangeDecision;
import com.ai.mall.agent.ops.model.HealAction;
import com.ai.mall.agent.ops.model.HealLevel;
import com.ai.mall.agent.ops.service.event.EventBus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 变更管控Agent：基于风险评分 + 审批门控的变更安全管控
 *
 * 风险评分：综合考虑爆炸半径(blastRadius)、自愈级别(healLevel)、历史成功率、影响服务数，
 * 计算变更操作的量化风险值 [0, 1]。
 *
 * 审批门控规则：
 * - riskScore < 0.3: 自动通过
 * - 0.3 <= riskScore < 0.6: 值班工程师审批
 * - 0.6 <= riskScore < 0.8: 团队负责人审批
 * - riskScore >= 0.8: CTO审批
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeAgent {

    private final EventBus eventBus;

    /**
     * 事件驱动入口：消费 aiops.commands 上的处置动作，产出管控决策发布到 aiops.audit。
     * 与 HealAgent 之间完全经由事件总线解耦。
     */
    @PostConstruct
    public void subscribeToHealActions() {
        eventBus.subscribe(EventBus.AIOPS_COMMANDS, HealAction.class, this::evaluateChange);
    }

    /** 历史成功率记录：Playbook名称 → 历史执行成功率 */
    private final Map<String, Double> historicalSuccessRate = new HashMap<>();

    /** 审批门控决策记录 */
    private final List<GateDecisionRecord> decisionRecords = new ArrayList<>();

    public ChangeDecision evaluateChange(HealAction healAction) {
        log.info("ChangeAgent evaluating heal action: {}", healAction.getId());

        // Step1: 计算综合风险评分
        double riskScore = calculateRiskScore(healAction);
        log.info("Risk score calculated: {} for action {}", formatDouble(riskScore), healAction.getAction());

        // Step2: 审批门控决策
        GateDecision gateDecision = applyApprovalGate(riskScore, healAction);
        String approver = gateDecision.approver;
        String status = gateDecision.status;

        // Step3: 记录审批门控决策
        GateDecisionRecord record = new GateDecisionRecord(
                healAction.getId(), riskScore, approver, status,
                gateDecision.reason, LocalDateTime.now()
        );
        decisionRecords.add(record);
        log.info("Gate decision recorded: riskScore={}, approver={}, status={}",
                formatDouble(riskScore), approver, status);

        ChangeDecision decision = ChangeDecision.builder()
                .id(UUID.randomUUID().toString())
                .alertId(healAction.getAlertId())
                .healActionId(healAction.getId())
                .riskScore(riskScore)
                .approver(approver)
                .status(status)
                .timestamp(LocalDateTime.now())
                .reason(generateReason(healAction, riskScore, status, gateDecision.reason))
                .build();

        eventBus.publish("aiops.audit", decision);
        log.info("Change decision: riskScore={}, approver={}, status={}",
                formatDouble(riskScore), approver, status);
        return decision;
    }

    /**
     * 综合风险评分计算：
     * riskScore = w1 * blastRadius + w2 * healLevelRisk + w3 * (1 - successRate) + w4 * affectedServiceFactor
     *
     * 考虑因素：
     * 1. blastRadius（爆炸半径）：影响范围越大风险越高
     * 2. healLevel（自愈级别）：L2级别风险 > L1 > L0
     * 3. 历史成功率：成功率越低风险越高
     * 4. 影响服务数：影响服务越多风险越高
     */
    private double calculateRiskScore(HealAction healAction) {
        // 因素1: 爆炸半径 (权重0.35)
        double blastRadiusFactor = healAction.getBlastRadius();

        // 因素2: 自愈级别风险 (权重0.25)
        double healLevelRisk = switch (healAction.getLevel()) {
            case L0_AUTO -> 0.1;    // 自动执行，低风险
            case L1_ONCALL -> 0.4;  // 需值班确认，中等风险
            case L2_APPROVAL -> 0.7; // 需负责人审批，高风险
        };

        // 因素3: 历史成功率 (权重0.25)，成功率越低风险越高
        double successRate = historicalSuccessRate.getOrDefault(healAction.getPlaybook(), 0.5);
        double failureRisk = 1.0 - successRate;

        // 因素4: 影响服务数因子 (权重0.15)，基于blastRadius推算影响服务数
        int affectedServices = (int) Math.ceil(healAction.getBlastRadius() / 0.15);
        double affectedServiceFactor = Math.min(1.0, affectedServices * 0.12);

        // 加权计算综合风险评分
        double riskScore = 0.35 * blastRadiusFactor
                + 0.25 * healLevelRisk
                + 0.25 * failureRisk
                + 0.15 * affectedServiceFactor;

        return Math.min(1.0, riskScore);
    }

    /**
     * 审批门控规则：
     * - riskScore < 0.3: 自动通过
     * - 0.3 <= riskScore < 0.6: 值班工程师审批
     * - 0.6 <= riskScore < 0.8: 团队负责人审批
     * - riskScore >= 0.8: CTO审批
     */
    private GateDecision applyApprovalGate(double riskScore, HealAction healAction) {
        if (riskScore < 0.3) {
            return new GateDecision("auto", "approved",
                    String.format("风险评分%.2f低于0.3阈值，自动通过", riskScore));
        }
        if (riskScore < 0.6) {
            return new GateDecision("oncall-engineer", "pending_approval",
                    String.format("风险评分%.2f在[0.3,0.6)区间，需值班工程师审批", riskScore));
        }
        if (riskScore < 0.8) {
            return new GateDecision("team-lead", "pending_approval",
                    String.format("风险评分%.2f在[0.6,0.8)区间，需团队负责人审批", riskScore));
        }
        return new GateDecision("cto", "pending_approval",
                String.format("风险评分%.2f达到0.8以上，需CTO审批", riskScore));
    }

    /**
     * 更新历史成功率（变更执行完成后调用）
     */
    public void updateSuccessRate(String playbookName, boolean success) {
        double currentRate = historicalSuccessRate.getOrDefault(playbookName, 0.5);
        // 指数移动平均更新成功率
        double newRate = 0.3 * (success ? 1.0 : 0.0) + 0.7 * currentRate;
        historicalSuccessRate.put(playbookName, newRate);
        log.info("Updated success rate for {}: {}", playbookName, formatDouble(newRate));
    }

    /**
     * 获取审批门控决策记录
     */
    public List<GateDecisionRecord> getDecisionRecords() {
        return Collections.unmodifiableList(decisionRecords);
    }

    private String generateReason(HealAction healAction, double riskScore, String status, String gateReason) {
        return String.format("%s: risk=%.2f, level=%s, action=%s | %s",
                status.toUpperCase(), riskScore, healAction.getLevel(),
                healAction.getAction(), gateReason);
    }

    /** 审批门控决策 */
    private record GateDecision(String approver, String status, String reason) {}

    /** 审批门控决策记录 */
    public record GateDecisionRecord(
            String healActionId,
            double riskScore,
            String approver,
            String status,
            String reason,
            LocalDateTime timestamp
    ) {}

    /**
     * 格式化小数为固定两位小数字符串。
     * SLF4J 只识别 {} 占位符，不支持 Python 风格的 {:.2f}，需先自行格式化。
     */
    private static String formatDouble(double value) {
        return String.format("%.2f", value);
    }
}
