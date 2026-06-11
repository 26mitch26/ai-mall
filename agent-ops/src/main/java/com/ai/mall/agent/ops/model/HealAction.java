package com.ai.mall.agent.ops.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealAction {
    private String id;
    private String alertId;
    private HealLevel level;
    private String action;
    private String playbook;
    private double blastRadius;
    private String status;
    private boolean dryRunPassed;
}
