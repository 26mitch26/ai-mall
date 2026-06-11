package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.ChangeDecision;
import com.ai.mall.agent.ops.model.HealAction;
import com.ai.mall.agent.ops.model.HealLevel;
import com.ai.mall.agent.ops.service.event.EventBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeAgent {

    private final EventBus eventBus;

    public ChangeDecision evaluateChange(HealAction healAction) {
        log.info("ChangeAgent evaluating heal action: {}", healAction.getId());

        double riskScore = calculateRiskScore(healAction);
        String approver = determineApprover(healAction.getLevel(), riskScore);
        String status = approveOrReject(healAction, riskScore);

        ChangeDecision decision = ChangeDecision.builder()
                .id(UUID.randomUUID().toString())
                .alertId(healAction.getAlertId())
                .healActionId(healAction.getId())
                .riskScore(riskScore)
                .approver(approver)
                .status(status)
                .timestamp(LocalDateTime.now())
                .reason(generateReason(healAction, riskScore, status))
                .build();

        eventBus.publish("aiops.audit", decision);
        log.info("Change decision: {}", decision);
        return decision;
    }

    private double calculateRiskScore(HealAction healAction) {
        double baseScore = healAction.getBlastRadius();
        if (healAction.getLevel() == HealLevel.L0_AUTO) baseScore *= 0.5;
        if (healAction.getLevel() == HealLevel.L2_APPROVAL) baseScore *= 1.5;
        return Math.min(1.0, baseScore);
    }

    private String determineApprover(HealLevel level, double riskScore) {
        return switch (level) {
            case L0_AUTO -> "auto";
            case L1_ONCALL -> "oncall-engineer";
            case L2_APPROVAL -> "team-lead";
        };
    }

    private String approveOrReject(HealAction healAction, double riskScore) {
        if (riskScore < 0.3) return "approved";
        if (riskScore < 0.6 && healAction.getLevel() == HealLevel.L1_ONCALL) return "pending";
        if (riskScore >= 0.6) return "rejected";
        return "approved";
    }

    private String generateReason(HealAction healAction, double riskScore, String status) {
        return String.format("%s: risk=%.2f, level=%s",
                status.toUpperCase(), riskScore, healAction.getLevel());
    }
}
