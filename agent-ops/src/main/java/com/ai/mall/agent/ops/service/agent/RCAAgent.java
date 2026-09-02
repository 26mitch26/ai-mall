package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.AlertEvent;
import com.ai.mall.agent.ops.model.RCAResult;
import com.ai.mall.agent.ops.service.event.EventBus;
import com.ai.mall.agent.ops.service.knowledge.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /** 先验概率 P(Cause)：基于历史故障分布的先验知识 */
    private static final Map<String, Double> PRIOR_PROBABILITY = new HashMap<>() {{
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

        // Step2: 基于Neo4j知识图谱获取候选根因节点
        List<String> candidateCauses = findCandidateCausesFromGraph(alert.getTargetService());

        // Step3: 贝叶斯推理——使用CPT先验 + 告警证据更新后验概率
        Map<String, Double> posteriorProbabilities = bayesianInference(alert, candidateCauses);

        // Step4: 选取后验概率最高的根因
        String rootCause = selectRootCause(posteriorProbabilities, alert, impactChain);
        double confidence = posteriorProbabilities.getOrDefault(rootCause, 0.0);

        // Step5: 生成建议动作
        List<String> suggestedActions = suggestActions(confidence, impactChain);

        RCAResult result = RCAResult.builder()
                .alertId(alert.getId())
                .rootCause(rootCause)
                .confidence(confidence)
                .impactChain(impactChain)
                .suggestedActions(suggestedActions)
                .build();

        eventBus.publish("aiops.events", result);
        log.info("RCA result: rootCause={}, confidence={:.2f}, impactChain={}", rootCause, confidence, impactChain);
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
     * 基于Neo4j知识图谱获取候选根因节点
     * 查询与服务关联的上游依赖和故障传播关系，获取候选根因列表
     */
    private List<String> findCandidateCausesFromGraph(String service) {
        List<String> candidates = knowledgeGraphService.findUpstreamCauses(service);
        if (candidates.isEmpty()) {
            // 知识图谱无数据时回退到先验概率表中的所有候选根因
            return new ArrayList<>(PRIOR_PROBABILITY.keySet());
        }
        return candidates;
    }

    /**
     * 贝叶斯推理：基于条件概率表(CPT)和先验概率，结合告警证据计算后验概率
     *
     * 贝叶斯公式：P(Cause | Alert) = P(Alert | Cause) * P(Cause) / P(Alert)
     * 其中 P(Alert) = Σ P(Alert | Cause_i) * P(Cause_i) 为归一化常数
     */
    private Map<String, Double> bayesianInference(AlertEvent alert, List<String> candidateCauses) {
        String metricType = extractMetricType(alert.getMetricName());
        Map<String, Double> likelihoods = CPT.getOrDefault(metricType, Collections.emptyMap());

        Map<String, Double> unnormalized = new LinkedHashMap<>();
        double evidence = 0.0;

        for (String cause : candidateCauses) {
            // P(Alert | Cause) 从CPT获取，若无则使用默认低概率
            double pAlertGivenCause = likelihoods.getOrDefault(cause, 0.05);
            // P(Cause) 从先验概率表获取
            double pCause = PRIOR_PROBABILITY.getOrDefault(cause, 0.01);

            double joint = pAlertGivenCause * pCause;
            unnormalized.put(cause, joint);
            evidence += joint;
        }

        // 归一化得到后验概率 P(Cause | Alert)
        Map<String, Double> posterior = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : unnormalized.entrySet()) {
            posterior.put(entry.getKey(), evidence > 0 ? entry.getValue() / evidence : 0.0);
        }

        log.debug("Bayesian inference for metric '{}': posterior={}", metricType, posterior);
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
}
