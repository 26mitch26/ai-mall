package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.AlertEvent;
import com.ai.mall.agent.ops.model.ChangeDecision;
import com.ai.mall.agent.ops.model.HealAction;
import com.ai.mall.agent.ops.model.IncidentState;
import com.ai.mall.agent.ops.model.RCAResult;
import com.ai.mall.agent.ops.service.event.EventBus;
import com.ai.mall.common.circuitbreaker.ModelCircuitBreaker;
import com.ai.mall.common.circuitbreaker.ModelRouterService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 故障处置编排器：负责触发链路首环节，并把分散在各环节的事件产出汇聚为 IncidentState。
 *
 * <h3>与旧版编排的区别</h3>
 * 旧版由 Orchestrator 依次「直接方法调用」RCA/Heal/Change，Agent 之间强耦合，
 * EventBus 形同虚设（只做单向审计落盘）。改造后 Agent 之间只通过 EventBus 通信：
 * <pre>
 *   Orchestrator.triggerIncident() 只触发 MonitorAgent 检测异常；
 *   MonitorAgent 发布 AlertEvent 到 aiops.alerts      -> RCAAgent 消费；
 *   RCAAgent 发布 RCAResult   到 aiops.events         -> HealAgent 消费；
 *   HealAgent 发布 HealAction 到 aiops.commands       -> ChangeAgent 消费；
 *   ChangeAgent 发布决策      到 aiops.audit，链路闭环。
 * </pre>
 * Orchestrator 自身订阅全部四个 Topic，职责从「编排执行」收缩为「状态汇聚」：
 * 把各环节产出归并到同一 IncidentState 上，供查询接口读取。
 *
 * <h3>同步与异步语义</h3>
 * <ul>
 *   <li><b>local-mode</b>（{@code aiops.eventbus.local-mode=true}）：事件链在触发线程内
 *       同步完成，{@link #triggerIncident} 返回终态，便于测试与单机演示。</li>
 *   <li><b>kafka 模式</b>（默认，生产）：后续环节异步推进，
 *       {@link #triggerIncident} 返回 detected 初态，通过 {@link #getIncident} 轮询进展。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Orchestrator {

    private final MonitorAgent monitorAgent;
    private final EventBus eventBus;
    private final ModelRouterService modelRouterService;

    private final Map<String, IncidentState> incidents = new ConcurrentHashMap<>();

    /**
     * 处置链路阶段序。事件驱动下各环节的完成通知可能乱序到达
     * （订阅注册顺序、Kafka 分区投递顺序均不可控），
     * 状态更新必须保证只前进不回退，否则深层环节先完成时，
     * 浅层环节的后到通知会把终态改回中间态。
     */
    private static final Map<String, Integer> STAGE_ORDER = Map.of(
            "detected", 0,
            "analyzed", 1,
            "healing", 2,
            "resolved", 3,
            "pending_approval", 3
    );

    /**
     * 订阅处置链路的全部事件，把各环节产出汇聚到对应的 IncidentState 上。
     */
    @PostConstruct
    public void subscribeToPipelineEvents() {
        eventBus.subscribe(EventBus.AIOPS_ALERTS, AlertEvent.class, this::onAlertDetected);
        eventBus.subscribe(EventBus.AIOPS_EVENTS, RCAResult.class, this::onRcaCompleted);
        eventBus.subscribe(EventBus.AIOPS_COMMANDS, HealAction.class, this::onHealPlanned);
        eventBus.subscribe(EventBus.AIOPS_AUDIT, ChangeDecision.class, this::onChangeEvaluated);
        log.info("Orchestrator subscribed to incident pipeline topics");
    }

    /**
     * 触发故障处置：只驱动首环节（异常检测），后续环节由事件总线自动衔接。
     *
     * @return local-mode 下为汇聚完成的完整状态；kafka 模式下为 detected 初态
     *         （后续异步补全，可用 {@link #getIncident} 轮询）；未检出异常时返回 null
     */
    public IncidentState triggerIncident(String metricName, double metricValue, String service) {
        log.info("Orchestrator triggering incident for {}: {} on {}", metricName, metricValue, service);

        // 编排前检查模型可用性，记录熔断状态
        checkModelAvailability();

        AlertEvent alert = monitorAgent.detectAnomaly(metricName, metricValue, service);
        if (alert == null) {
            log.info("No anomaly detected, incident not triggered");
            return null;
        }
        return incidents.get(alert.getId());
    }

    private void onAlertDetected(AlertEvent alert) {
        IncidentState incident = incidents.computeIfAbsent(alert.getId(), this::newIncident);
        incident.setAlert(alert);
        advanceStage(incident, "detected");
        log.info("Incident {} created/updated from alert", incident.getId());
    }

    private void onRcaCompleted(RCAResult rcaResult) {
        IncidentState incident = incidents.computeIfAbsent(rcaResult.getAlertId(), this::newIncident);
        incident.setRcaResult(rcaResult);
        advanceStage(incident, "analyzed");
        log.info("Incident {} analyzed, rootCause={}", incident.getId(), rcaResult.getRootCause());
    }

    private void onHealPlanned(HealAction healAction) {
        IncidentState incident = incidents.computeIfAbsent(healAction.getAlertId(), this::newIncident);
        incident.setHealAction(healAction);
        advanceStage(incident, "healing");
        log.info("Incident {} healing, level={}, playbook={}",
                incident.getId(), healAction.getLevel(), healAction.getPlaybook());
    }

    private void onChangeEvaluated(ChangeDecision changeDecision) {
        IncidentState incident = incidents.computeIfAbsent(changeDecision.getAlertId(), this::newIncident);
        incident.setChangeDecision(changeDecision);
        boolean approved = "approved".equals(changeDecision.getStatus());
        advanceStage(incident, approved ? "resolved" : "pending_approval");
        incident.setEndTime(LocalDateTime.now());
        log.info("Incident {} finished with status={}", incident.getId(), incident.getStatus());
    }

    /**
     * 状态单调推进：仅当目标阶段不早于当前阶段时才更新，
     * 使乱序到达的环节通知不会把状态回退。
     */
    private void advanceStage(IncidentState incident, String target) {
        Integer current = STAGE_ORDER.get(incident.getStatus());
        Integer targetOrder = STAGE_ORDER.get(target);
        if (targetOrder == null) {
            return;
        }
        if (current == null || targetOrder >= current) {
            incident.setStatus(target);
        }
    }

    /**
     * 惰性创建 incident 骨架：事件驱动下各环节的处理顺序不保证
     * （订阅注册顺序、Kafka 分区投递顺序都可能变化），任何环节都可能是
     * 最先到达的，因此统一用 computeIfAbsent 兜底，保证乱序不丢数据。
     */
    private IncidentState newIncident(String alertId) {
        return IncidentState.builder()
                .id(alertId)
                .status("detected")
                .startTime(LocalDateTime.now())
                .build();
    }

    /**
     * 检查模型可用性，监控熔断器状态
     * 当MiMo模型熔断器处于OPEN或HALF_OPEN状态时记录告警日志
     */
    private void checkModelAvailability() {
        ModelCircuitBreaker mimoBreaker = modelRouterService.getMimoCircuitBreaker();
        ModelCircuitBreaker localBreaker = modelRouterService.getLocalCircuitBreaker();
        ModelCircuitBreaker.CircuitState mimoState = mimoBreaker.getState();
        ModelCircuitBreaker.CircuitState localState = localBreaker.getState();

        if (mimoState == ModelCircuitBreaker.CircuitState.OPEN) {
            log.warn("MiMo模型熔断器处于OPEN状态，连续失败次数: {}，所有模型请求将降级到本地模型",
                    mimoBreaker.getFailureCount());
        } else if (mimoState == ModelCircuitBreaker.CircuitState.HALF_OPEN) {
            log.warn("MiMo模型熔断器处于HALF_OPEN状态，正在探测恢复，失败次数: {}",
                    mimoBreaker.getFailureCount());
        }

        if (localState != ModelCircuitBreaker.CircuitState.CLOSED) {
            log.error("本地模型熔断器状态异常: {}，失败次数: {}，系统可能面临全量熔断风险",
                    localState, localBreaker.getFailureCount());
        }

        log.info("模型可用性检查 - MiMo: 状态={}, 失败次数={}; 本地模型: 状态={}, 失败次数={}",
                mimoState, mimoBreaker.getFailureCount(), localState, localBreaker.getFailureCount());
    }

    public IncidentState getIncident(String id) {
        return incidents.get(id);
    }

    public Map<String, IncidentState> getAllIncidents() {
        return incidents;
    }
}
