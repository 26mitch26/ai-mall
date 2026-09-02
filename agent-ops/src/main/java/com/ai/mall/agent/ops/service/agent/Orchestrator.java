package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.*;
import com.ai.mall.agent.ops.service.event.EventBus;
import com.ai.mall.common.common.circuitbreaker.ModelCircuitBreaker;
import com.ai.mall.common.common.circuitbreaker.ModelRouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class Orchestrator {

    private final MonitorAgent monitorAgent;
    private final RCAAgent rcaAgent;
    private final HealAgent healAgent;
    private final ChangeAgent changeAgent;
    private final EventBus eventBus;
    private final ModelRouterService modelRouterService;

    private final Map<String, IncidentState> incidents = new ConcurrentHashMap<>();

    public IncidentState triggerIncident(String metricName, double metricValue, String service) {
        log.info("Orchestrator triggering incident for {}: {} on {}", metricName, metricValue, service);

        // 编排前检查模型可用性，记录熔断状态
        checkModelAvailability();

        AlertEvent alert = monitorAgent.detectAnomaly(metricName, metricValue, service);
        if (alert == null) {
            log.info("No anomaly detected, incident not triggered");
            return null;
        }

        IncidentState incident = IncidentState.builder()
                .id(alert.getId())
                .alert(alert)
                .status("detected")
                .startTime(LocalDateTime.now())
                .build();
        incidents.put(incident.getId(), incident);

        RCAResult rcaResult = rcaAgent.analyzeRootCause(alert);
        incident.setRcaResult(rcaResult);
        incident.setStatus("analyzed");

        HealAction healAction = healAgent.heal(rcaResult);
        incident.setHealAction(healAction);
        incident.setStatus("healing");

        ChangeDecision changeDecision = changeAgent.evaluateChange(healAction);
        incident.setChangeDecision(changeDecision);

        if ("approved".equals(changeDecision.getStatus())) {
            incident.setStatus("resolved");
        } else {
            incident.setStatus("pending_approval");
        }
        incident.setEndTime(LocalDateTime.now());

        log.info("Incident processed: {}", incident);
        return incident;
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
