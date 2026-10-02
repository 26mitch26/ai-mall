package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.AlertEvent;
import com.ai.mall.agent.ops.model.RCAResult;
import com.ai.mall.agent.ops.model.Severity;
import com.ai.mall.agent.ops.service.event.EventBus;
import com.ai.mall.agent.ops.service.knowledge.KnowledgeGraphService;
import com.ai.mall.agent.ops.service.knowledge.KnowledgeGraphService.CauseCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 根因分析Agent单元测试：验证知识图谱先验与贝叶斯推理真正打通。
 *
 * <h3>背景</h3>
 * 早期实现用 {@code findUpstreamCauses()} 的返回值（<b>服务名</b>，如 mysql-primary）
 * 作为贝叶斯候选，而条件概率表 CPT 的 key 是<b>故障模式</b>（如 connection_pool_exhaustion），
 * 两者语义不同、永远匹配不上。结果所有候选只能取默认似然，后验概率退化为 1/N 均分，
 * 根因定位失去意义，下游 Playbook 匹配也随之塌到兜底项。
 *
 * <h3>修复</h3>
 * 图谱新增 {@code (:Service)-[:HAS_FAILURE_MODE {prior}]->(:FailureMode)} 关系，
 * 候选改为故障模式且自带图上先验，与 CPT 的 key 对齐。
 *
 * <h3>本测试守卫</h3>
 * 锁定"后验概率必须由 CPT 似然 × 图谱先验真实拉开差距"这一性质，
 * 防止后续改动再次把两类语义混用导致推理退化。
 */
@ExtendWith(MockitoExtension.class)
class RCAAgentTest {

    @Mock
    private EventBus eventBus;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @InjectMocks
    private RCAAgent rcaAgent;

    /** order-service 在图谱上的故障模式先验（与 KnowledgeGraphService.FAILURE_MODE_PRIORS 一致） */
    private static final List<CauseCandidate> ORDER_SERVICE_CAUSES = List.of(
            new CauseCandidate("deployment_regression", 0.30),
            new CauseCandidate("resource_contention", 0.25),
            new CauseCandidate("traffic_spike", 0.20),
            new CauseCandidate("gc_overhead", 0.15),
            new CauseCandidate("connection_pool_exhaustion", 0.10)
    );

    private AlertEvent cpuAlert(String service) {
        return AlertEvent.builder()
                .id("alert-1")
                .metricName("cpu_usage")
                .metricValue(92.0)
                .targetService(service)
                .severity(Severity.CRITICAL)
                .timestamp(LocalDateTime.now())
                .status("detected")
                .build();
    }

    @Test
    @DisplayName("CPU告警应定位到图谱先验与CPT似然乘积最高的故障模式")
    void shouldSelectHighestPosteriorFailureMode() {
        when(knowledgeGraphService.findCandidateCauses("order-service"))
                .thenReturn(ORDER_SERVICE_CAUSES);
        when(knowledgeGraphService.findImpactPaths("order-service"))
                .thenReturn(List.of(List.of("order-service", "payment-service")));

        RCAResult result = rcaAgent.analyzeRootCause(cpuAlert("order-service"));

        // CPT["cpu"] 中 deployment_regression 似然最高(0.85)，图谱先验也最高(0.30)，乘积最大
        assertEquals("deployment_regression", result.getRootCause());

        // 手工核算：joint = 0.85*0.30=0.255, 0.60*0.25=0.150, 0.70*0.20=0.140,
        //           0.40*0.15=0.060, 0.05(默认)*0.10=0.005；evidence=0.610
        assertEquals(0.255 / 0.610, result.getConfidence(), 1e-6);
    }

    @Test
    @DisplayName("后验概率必须由先验与似然真实拉开差距，而非退化成均匀分分")
    void posteriorShouldBeDifferentiatedNotUniform() {
        when(knowledgeGraphService.findCandidateCauses("order-service"))
                .thenReturn(ORDER_SERVICE_CAUSES);
        when(knowledgeGraphService.findImpactPaths("order-service"))
                .thenReturn(List.of(List.of("order-service", "payment-service")));

        RCAResult result = rcaAgent.analyzeRootCause(cpuAlert("order-service"));

        double confidence = result.getConfidence();

        // 若候选与 CPT 错配，所有后验会退化为 1/5 = 0.20
        assertTrue(Math.abs(confidence - 0.20) > 0.05,
                "后验概率应明显偏离均匀分布 0.20，实际=" + confidence);
        // 且最优项应显著高于次优项，体现先验×似然的区分度
        assertTrue(confidence > 0.35,
                "最优故障模式的后验应显著领先，实际=" + confidence);
    }

    @Test
    @DisplayName("根因输出必须是故障模式而非服务名，否则下游Playbook匹配会失效")
    void rootCauseShouldBeFailureModeNotServiceName() {
        when(knowledgeGraphService.findCandidateCauses("order-service"))
                .thenReturn(ORDER_SERVICE_CAUSES);
        when(knowledgeGraphService.findImpactPaths("order-service"))
                .thenReturn(List.of(List.of("order-service", "payment-service")));

        RCAResult result = rcaAgent.analyzeRootCause(cpuAlert("order-service"));

        // 故障模式必须能命中 HealAgent 的 Playbook 触发词，否则会塌到兜底项
        assertTrue(result.getRootCause().contains("deployment_regression"),
                "根因应为故障模式，Playbook 才能按触发词匹配；实际=" + result.getRootCause());
    }

    @Test
    @DisplayName("图谱无数据时应退回全局先验表，保证链路可用")
    void shouldFallbackToGlobalPriorWhenGraphEmpty() {
        when(knowledgeGraphService.findCandidateCauses(anyString())).thenReturn(List.of());
        when(knowledgeGraphService.findImpactPaths(anyString())).thenReturn(List.of());

        RCAResult result = rcaAgent.analyzeRootCause(cpuAlert("unknown-service"));

        // 兜底路径不应抛异常，且仍能给出根因
        assertTrue(result.getRootCause() != null && !result.getRootCause().isBlank());
    }
}
