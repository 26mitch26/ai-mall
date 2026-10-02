package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.AlertEvent;
import com.ai.mall.agent.ops.model.RCAResult;
import com.ai.mall.agent.ops.service.event.EventBus;
import com.ai.mall.agent.ops.service.knowledge.KnowledgeGraphService;
import com.ai.mall.agent.ops.service.knowledge.KnowledgeGraphService.CauseCandidate;
import com.ai.mall.agent.ops.service.llm.OpsLlmAdvisor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

/**
 * 根因分析Agent：基于贝叶斯推理 + Neo4j知识图谱的智能根因定位
 *
 * 贝叶斯推理：使用条件概率表(CPT)作为先验知识，结合告警证据进行后验概率更新，
 * 实现多候选根因的概率排序，避免硬编码单一因果关系。
 *
 * 知识图谱定位：基于Neo4j存储的服务拓扑和故障传播关系，通过图遍历定位根因节点，
 * 结合贝叶斯概率进行排序，输出高置信度的根因分析结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RCAAgent {

    private final EventBus eventBus;
    private final KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private OpsLlmAdvisor opsLlmAdvisor;

    /** CPT 中未收录该故障模式时使用的默认似然，避免先验为 0 导致候选被完全排除 */
    private static final double DEFAULT_LIKELIHOOD = 0.05;

    /**
     * 事件驱动入口：消费 aiops.alerts 上的告警事件，产出根因结果发布到 aiops.events。
     * 与 MonitorAgent 之间不存在直接方法调用，完全经由事件总线解耦。
     */
    @PostConstruct
    public void subscribeToAlerts() {
        eventBus.subscribe(EventBus.AIOPS_ALERTS, AlertEvent.class, this::analyzeRootCause);
    }

    /**
     * 条件概率表(CPT)：P(Alert | Cause)
     * 定义不同告警指标在不同根因下的条件概率，用于贝叶斯推理
     */
    private static final Map<String, Map<String, Double>> CPT = Map.of(
            "cpu", Map.of(
                    "deployment_regression", 0.85,
                    "traffic_spike", 0.70,
                    "resource_contention", 0.60,
                    "gc_overhead", 0.40
            ),
            "memory", Map.of(
                    "memory_leak", 0.90,
                    "cache_bloat", 0.65,
                    "deployment_regression", 0.35,
                    "resource_contention", 0.50
            ),
            "disk", Map.of(
                    "log_bloat", 0.80,
                    "data_growth", 0.70,
                    "disk_failure", 0.55,
                    "cache_bloat", 0.30
            ),
            "network", Map.of(
                    "dns_failure", 0.75,
                    "connection_pool_exhaustion", 0.65,
                    "bandwidth_saturation", 0.55,
                    "firewall_misconfig", 0.40
            )
    );

    /**
     * 兜底先验概率 P(Cause)：仅在知识图谱无数据（Neo4j 不可用且无降级数据）时使用。
     *
     * <p>正常路径下先验来自图谱关系 {@code (:Service)-[:HAS_FAILURE_MODE {prior}]->(:FailureMode)}
     * 的属性，见 {@link KnowledgeGraphService#findCandidateCauses(String)}。
     * 此表是全局故障分布的粗略兜底，不如按服务区分的图上先验精确。
     */
    private static final Map<String, Double> FALLBACK_PRIOR_PROBABILITY = new HashMap<>() {{
        put("deployment_regression", 0.25);
        put("memory_leak", 0.15);
        put("traffic_spike", 0.12);
        put("resource_contention", 0.10);
        put("cache_bloat", 0.08);
        put("gc_overhead", 0.07);
        put("log_bloat", 0.06);
        put("dns_failure", 0.05);
        put("connection_pool_exhaustion", 0.04);
        put("data_growth", 0.03);
        put("disk_failure", 0.03);
        put("bandwidth_saturation", 0.01);
        put("firewall_misconfig", 0.01);
    }};

    public RCAResult analyzeRootCause(AlertEvent alert) {
        log.info("RCAAgent analyzing root cause for alert: {}", alert.getId());

        // Step1: 基于Neo4j知识图谱获取影响链路
        List<String> impactChain = findImpactChain(alert.getTargetService());

        // Step2: 基于Neo4j知识图谱获取候选故障模式及其图上先验
        List<CauseCandidate> candidateCauses = resolveCandidateCauses(alert.getTargetService());

        // Step3: 贝叶斯推理——CPT似然 + 图谱先验，结合告警证据更新后验概率
        Map<String, Double> posteriorProbabilities = bayesianInference(alert, candidateCauses);

        // Step4: 选取后验概率最高的根因
        String rootCause = selectRootCause(posteriorProbabilities, alert, impactChain);
        double confidence = posteriorProbabilities.getOrDefault(rootCause, 0.0);

        // Step5: 生成建议动作
        List<String> suggestedActions = suggestActions(confidence, impactChain);
        String analysisSummary = opsLlmAdvisor == null
                ? String.format("%s 的 %s 指标异常，定位根因为 %s（置信度 %.0f%%）。",
                    alert.getTargetService(), alert.getMetricName(), rootCause, confidence * 100)
                : opsLlmAdvisor.summarize(alert, rootCause, confidence, impactChain, suggestedActions);

        RCAResult result = RCAResult.builder()
                .alertId(alert.getId())
                .rootCause(rootCause)
                .confidence(confidence)
                .impactChain(impactChain)
                .suggestedActions(suggestedActions)
                .analysisSummary(analysisSummary)
                .build();

        eventBus.publish("aiops.events", result);
        log.info("RCA result: rootCause={}, confidence={}, impactChain={}",
                rootCause, formatDouble(confidence), impactChain);
        return result;
    }

    /**
     * 基于Neo4j知识图谱获取影响链路
     * 通过Cypher查询服务拓扑图，找到从告警服务出发的最长影响路径
     */
    private List<String> findImpactChain(String service) {
        List<List<String>> paths = knowledgeGraphService.findImpactPaths(service);
        return paths.stream()
                .max(Comparator.comparingInt(List::size))
                .orElse(Collections.singletonList(service));
    }

    /**
     * 解析候选故障模式。
     *
     * <p>优先从知识图谱取「按服务区分的故障模式 + 图上先验」。若图谱无数据
     * （Neo4j 不可用且降级存储也为空），则退回全局兜底先验表，保证链路可用。
     *
     * <p>注意：不能直接用 {@code findUpstreamCauses()} 的返回值做候选——它返回的是
     * <b>服务名</b>，与 CPT 的故障模式 key 语义不同，两者相乘会使贝叶斯推理退化为
     * 概率均分，根因定位失去意义。
     */
    private List<CauseCandidate> resolveCandidateCauses(String service) {
        List<CauseCandidate> candidates = knowledgeGraphService.findCandidateCauses(service);
        if (candidates.isEmpty()) {
            log.warn("Knowledge graph returned no failure mode for service '{}', "
                    + "falling back to global prior table", service);
            return FALLBACK_PRIOR_PROBABILITY.entrySet().stream()
                    .map(e -> new CauseCandidate(e.getKey(), e.getValue()))
                    .toList();
        }
        return candidates;
    }

    /**
     * 贝叶斯推理：基于条件概率表(CPT)提供的似然与知识图谱提供的先验，计算后验概率。
     *
     * 贝叶斯公式：P(Cause | Alert) = P(Alert | Cause) * P(Cause) / P(Alert)
     * 其中：
     * - P(Alert | Cause) 来自 CPT，按告警指标类型取似然
     * - P(Cause)        来自知识图谱 HAS_FAILURE_MODE 关系的 prior 属性
     * - P(Alert) = Σ P(Alert | Cause_i) * P(Cause_i) 为归一化常数
     */
    private Map<String, Double> bayesianInference(AlertEvent alert, List<CauseCandidate> candidateCauses) {
        String metricType = extractMetricType(alert.getMetricName());
        Map<String, Double> likelihoods = CPT.getOrDefault(metricType, Collections.emptyMap());

        Map<String, Double> unnormalized = new LinkedHashMap<>();
        double evidence = 0.0;

        for (CauseCandidate candidate : candidateCauses) {
            // P(Alert | Cause) 从CPT获取，若无则使用默认低概率
            double pAlertGivenCause = likelihoods.getOrDefault(candidate.name(), DEFAULT_LIKELIHOOD);
            // P(Cause) 来自知识图谱关系属性，而非硬编码常量
            double pCause = candidate.prior();

            double joint = pAlertGivenCause * pCause;
            unnormalized.put(candidate.name(), joint);
            evidence += joint;
        }

        // 归一化得到后验概率 P(Cause | Alert)
        Map<String, Double> posterior = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : unnormalized.entrySet()) {
            posterior.put(entry.getKey(), evidence > 0 ? entry.getValue() / evidence : 0.0);
        }

        log.debug("Bayesian inference for metric '{}' over {} candidates: posterior={}",
                metricType, candidateCauses.size(), posterior);
        return posterior;
    }

    /**
     * 从告警指标名称中提取指标类型（cpu/memory/disk/network）
     */
    private String extractMetricType(String metricName) {
        String lower = metricName.toLowerCase();
        if (lower.contains("cpu")) return "cpu";
        if (lower.contains("memory") || lower.contains("mem") || lower.contains("heap")) return "memory";
        if (lower.contains("disk") || lower.contains("io")) return "disk";
        if (lower.contains("network") || lower.contains("net") || lower.contains("latency")) return "network";
        return "cpu"; // 默认按cpu类型处理
    }

    /**
     * 选取后验概率最高的根因
     * 优先使用贝叶斯推理结果，若后验概率均低于阈值则回退到知识图谱路径分析
     */
    private String selectRootCause(Map<String, Double> posterior, AlertEvent alert, List<String> impactChain) {
        Optional<Map.Entry<String, Double>> maxEntry = posterior.entrySet().stream()
                .max(Map.Entry.comparingByValue());

        if (maxEntry.isPresent() && maxEntry.get().getValue() > 0.1) {
            return maxEntry.get().getKey();
        }

        // 回退：基于知识图谱影响链路推断根因
        if (!impactChain.isEmpty()) {
            return "upstream_failure: " + impactChain.get(impactChain.size() - 1);
        }

        return "unknown_cause_requires_investigation";
    }

    private List<String> suggestActions(double confidence, List<String> impactChain) {
        List<String> actions = new ArrayList<>();
        if (confidence > 0.5) {
            actions.add("rollback");
            actions.add("profiling");
        } else {
            actions.add("investigate");
            actions.add("monitor");
        }
        return actions;
    }

    /**
     * 格式化小数为固定两位小数字符串。
     * SLF4J 只识别 {} 占位符，不支持 Python 风格的 {:.2f}，需先自行格式化。
     */
    private static String formatDouble(double value) {
        return String.format("%.2f", value);
    }
}
