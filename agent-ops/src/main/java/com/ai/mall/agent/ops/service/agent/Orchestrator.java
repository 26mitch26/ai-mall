package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.*;
import com.ai.mall.agent.ops.service.event.EventBus;
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

    private final Map<String, IncidentState> incidents = new ConcurrentHashMap<>();

    public IncidentState triggerIncident(String metricName, double metricValue, String service) {
        log.info("Orchestrator triggering incident for {}: {} on {}", metricName, metricValue, service);

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

    public IncidentState getIncident(String id) {
        return incidents.get(id);
    }

    public Map<String, IncidentState> getAllIncidents() {
        return incidents;
    }
}
