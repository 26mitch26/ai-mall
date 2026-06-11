package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.HealAction;
import com.ai.mall.agent.ops.model.HealLevel;
import com.ai.mall.agent.ops.model.RCAResult;
import com.ai.mall.agent.ops.service.event.EventBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealAgent {

    private final EventBus eventBus;

    public HealAction heal(RCAResult rcaResult) {
        log.info("HealAgent processing RCA result for alert: {}", rcaResult.getAlertId());

        HealLevel level = determineLevel(rcaResult);
        String action = matchPlaybook(rcaResult);
        double blastRadius = calculateBlastRadius(rcaResult);
        boolean dryRunPassed = dryRun(action);

        HealAction healAction = HealAction.builder()
                .id(UUID.randomUUID().toString())
                .alertId(rcaResult.getAlertId())
                .level(level)
                .action(action)
                .playbook(action)
                .blastRadius(blastRadius)
                .status(dryRunPassed ? "ready" : "dry_run_failed")
                .dryRunPassed(dryRunPassed)
                .build();

        eventBus.publish("aiops.commands", healAction);
        log.info("Heal action created: {}", healAction);
        return healAction;
    }

    private HealLevel determineLevel(RCAResult rcaResult) {
        double confidence = rcaResult.getConfidence();
        if (confidence > 0.7) return HealLevel.L0_AUTO;
        if (confidence > 0.4) return HealLevel.L1_ONCALL;
        return HealLevel.L2_APPROVAL;
    }

    private String matchPlaybook(RCAResult rcaResult) {
        if (rcaResult.getSuggestedActions().contains("rollback")) {
            return "rollback";
        } else if (rcaResult.getSuggestedActions().contains("restart")) {
            return "restart";
        }
        return "investigate";
    }

    private double calculateBlastRadius(RCAResult rcaResult) {
        int chainSize = rcaResult.getImpactChain().size();
        return Math.min(1.0, chainSize * 0.15);
    }

    private boolean dryRun(String action) {
        log.info("Executing dry-run for action: {}", action);
        return true;
    }
}
