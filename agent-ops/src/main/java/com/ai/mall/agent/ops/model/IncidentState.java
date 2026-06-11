package com.ai.mall.agent.ops.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentState {
    private String id;
    private AlertEvent alert;
    private RCAResult rcaResult;
    private HealAction healAction;
    private ChangeDecision changeDecision;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
