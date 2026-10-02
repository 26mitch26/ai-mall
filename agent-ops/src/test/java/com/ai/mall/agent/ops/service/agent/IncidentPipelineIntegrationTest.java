package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.HealLevel;
import com.ai.mall.agent.ops.model.IncidentState;
import com.ai.mall.agent.ops.service.event.EventBus;
import com.ai.mall.agent.ops.service.knowledge.KnowledgeGraphService;
import com.ai.mall.common.circuitbreaker.ModelCircuitBreaker;
import com.ai.mall.common.circuitbreaker.ModelRouterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 故障处置全链路集成测试：验证四个 Agent 经由 EventBus 事件驱动衔接成完整链路。
 *
 * <h3>验证目标</h3>
 * Orchestrator 与 RCA/Heal/Change 之间已无直接方法调用——触发入口只驱动
 * MonitorAgent 检测异常，随后：
 * <pre>
 *   AlertEvent     --(aiops.alerts)  -> RCAAgent    -> RCAResult
 *   RCAResult      --(aiops.events)  -> HealAgent   -> HealAction
 *   HealAction     --(aiops.commands)-> ChangeAgent -> ChangeDecision
 *   ChangeDecision --(aiops.audit)   -> Orchestrator 汇聚为终态
 * </pre>
 *
 * <h3>测试策略</h3>
 * 使用 EventBus 的 local-mode（跳过 Kafka，发布线程内同步分发）：
 * <ul>
 *   <li>链路行为与 kafka 模式完全一致，只是时序由异步变为同步；</li>
 *   <li>无需 Kafka 容器即可验证全链路，测试稳定可重复。</li>
 * </ul>
 * 组件手动装配并显式调用各 @PostConstruct 订阅方法，模拟 Spring 容器行为。
 */
class IncidentPipelineIntegrationTest {

    private Orchestrator orchestrator;
    private MonitorAgent monitorAgent;

    @BeforeEach
    void setUp() throws Exception {
        // local-mode：同步分发；ObjectMapper 注册 JavaTimeModule 以支持 LocalDateTime
        EventBus eventBus = new EventBus(mock(KafkaTemplate.class),
                new ObjectMapper().findAndRegisterModules());
        setLocalMode(eventBus);

        monitorAgent = new MonitorAgent(eventBus);

        // Neo4j 不可用 -> 内存降级图，验证降级路径下全链路依然可用
        KnowledgeGraphService knowledgeGraph = new KnowledgeGraphService(mock(Neo4jClient.class));
        knowledgeGraph.initServiceTopology();

        RCAAgent rcaAgent = new RCAAgent(eventBus, knowledgeGraph);
        HealAgent healAgent = new HealAgent(eventBus);
        ChangeAgent changeAgent = new ChangeAgent(eventBus);

        // 熔断器不打桩：getState 返回 null 时不命中任何告警分支，仅记录可用性日志
        ModelRouterService router = mock(ModelRouterService.class);
        ModelCircuitBreaker breaker = mock(ModelCircuitBreaker.class);
        when(router.getMimoCircuitBreaker()).thenReturn(breaker);
        when(router.getLocalCircuitBreaker()).thenReturn(breaker);

        orchestrator = new Orchestrator(monitorAgent, eventBus, router);

        // 模拟 Spring 容器：显式触发各组件的 @PostConstruct 订阅
        rcaAgent.subscribeToAlerts();
        healAgent.subscribeToRcaResults();
        changeAgent.subscribeToHealActions();
        orchestrator.subscribeToPipelineEvents();
    }

    @Test
    @DisplayName("告警应经事件总线流经 RCA/Heal/Change 三环节，产出门控终态")
    void fullPipelineShouldFlowThroughEventBus() {
        // 预热：先喂正常数据建立检测基线（滑动窗口与 EWMA 需至少 10 个样本）
        for (int i = 0; i < 15; i++) {
            monitorAgent.evaluate(50.0 + (i % 3));
        }

        // 触发异常：95 相对基线 ~51 偏离巨大，3-Sigma 与 EWMA 应同时投票通过
        IncidentState incident = orchestrator.triggerIncident("cpu_usage", 95.0, "order-service");

        assertNotNull(incident, "双算法投票应触发告警并产生 incident");

        // RCA 环节（消费 aiops.alerts）：图谱先验 x CPT 似然应定位到部署回归
        assertNotNull(incident.getRcaResult(), "RCA 应经由事件总线被触发");
        assertEquals("deployment_regression", incident.getRcaResult().getRootCause(),
                "CPU 告警 + order-service 应定位到部署回归");

        // Heal 环节（消费 aiops.events）：rollback Playbook 应按触发词命中
        assertNotNull(incident.getHealAction(), "Heal 应经由事件总线被触发");
        assertEquals("rollback", incident.getHealAction().getPlaybook(),
                "deployment_regression 应命中 rollback Playbook");
        assertEquals(HealLevel.L2_APPROVAL, incident.getHealAction().getLevel(),
                "置信度 0.38 < 0.4 应判定为 L2 需审批");

        // Change 环节（消费 aiops.commands）：风险评分应落在值班工程师审批区间
        assertNotNull(incident.getChangeDecision(), "Change 应经由事件总线被触发");
        assertEquals("pending_approval", incident.getChangeDecision().getStatus());
        assertEquals("oncall-engineer", incident.getChangeDecision().getApprover());

        // 状态汇聚：终态应由 Change 环节写入
        assertEquals("pending_approval", incident.getStatus());
        assertNotNull(incident.getEndTime(), "终态应记录结束时间");
    }

    /**
     * 通过反射打开 EventBus 的本地直连模式（生产默认走 Kafka，仅供测试切换）。
     */
    private static void setLocalMode(EventBus eventBus) throws Exception {
        Field field = EventBus.class.getDeclaredField("localMode");
        field.setAccessible(true);
        field.setBoolean(eventBus, true);
    }
}
