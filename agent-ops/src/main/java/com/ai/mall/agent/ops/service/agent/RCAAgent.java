package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.AlertEvent;
import com.ai.mall.agent.ops.model.RCAResult;
import com.ai.mall.agent.ops.service.event.EventBus;
import com.ai.mall.agent.ops.service.knowledge.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RCAAgent {

    private final EventBus eventBus;
    private final KnowledgeGraphService knowledgeGraphService;

    public RCAResult analyzeRootCause(AlertEvent alert) {
        log.info("RCAAgent analyzing root cause for alert: {}", alert.getId());

        List<String> impactChain = findImpactChain(alert.getTargetService());
        double confidence = calculateBayesianProbability(alert);
        List<String> suggestedActions = suggestActions(confidence, impactChain);

        RCAResult result = RCAResult.builder()
                .alertId(alert.getId())
                .rootCause(determineRootCause(alert, impactChain))
                .confidence(confidence)
                .impactChain(impactChain)
                .suggestedActions(suggestedActions)
                .build();

        eventBus.publish("aiops.events", result);
        log.info("RCA result: {}", result);
        return result;
    }

    private List<String> findImpactChain(String service) {
        List<List<String>> paths = knowledgeGraphService.findImpactPaths(service);
        return paths.stream()
                .max(Comparator.comparingInt(List::size))
                .orElse(Collections.singletonList(service));
    }

    private double calculateBayesianProbability(AlertEvent alert) {
        double pAlertGivenCause = 0.9;
        double pCause = 0.3;
        double pAlert = 0.5;

        return (pAlertGivenCause * pCause) / pAlert;
    }

    private String determineRootCause(AlertEvent alert, List<String> impactChain) {
        if (alert.getMetricName().contains("cpu")) {
            return "近期代码部署引入性能退化";
        } else if (alert.getMetricName().contains("memory")) {
            return "内存泄漏导致服务不稳定";
        } else if (alert.getMetricName().contains("disk")) {
            return "磁盘空间不足";
        }
        return "未知原因，需要进一步分析";
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
