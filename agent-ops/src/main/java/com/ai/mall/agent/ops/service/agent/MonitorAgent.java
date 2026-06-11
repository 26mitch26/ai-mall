package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.AlertEvent;
import com.ai.mall.agent.ops.model.Severity;
import com.ai.mall.agent.ops.service.event.EventBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorAgent {

    private final EventBus eventBus;

    private static final double THRESHOLD_3SIGMA = 3.0;
    private static final double EWMA_ALPHA = 0.3;

    public AlertEvent detectAnomaly(String metricName, double value, String service) {
        log.info("MonitorAgent detecting anomaly for {}: {} on {}", metricName, value, service);

        boolean sigmaVote = detectBy3Sigma(value);
        boolean ewmaVote = detectByEWMA(value);

        int votes = (sigmaVote ? 1 : 0) + (ewmaVote ? 1 : 0);

        if (votes >= 2) {
            AlertEvent alert = AlertEvent.builder()
                    .id(UUID.randomUUID().toString())
                    .metricName(metricName)
                    .metricValue(value)
                    .targetService(service)
                    .severity(calculateSeverity(value))
                    .timestamp(LocalDateTime.now())
                    .status("detected")
                    .build();

            eventBus.publish("aiops.alerts", alert);
            log.info("Anomaly detected and alert published: {}", alert);
            return alert;
        }

        log.info("No anomaly detected (votes: {}/2)", votes);
        return null;
    }

    private boolean detectBy3Sigma(double value) {
        double mean = 50.0;
        double stdDev = 15.0;
        double zScore = Math.abs((value - mean) / stdDev);
        return zScore > THRESHOLD_3SIGMA;
    }

    private boolean detectByEWMA(double value) {
        double ewma = 50.0;
        double threshold = 80.0;
        ewma = EWMA_ALPHA * value + (1 - EWMA_ALPHA) * ewma;
        return ewma > threshold;
    }

    private Severity calculateSeverity(double value) {
        if (value > 95) return Severity.CRITICAL;
        if (value > 85) return Severity.HIGH;
        if (value > 75) return Severity.MEDIUM;
        return Severity.LOW;
    }
}
