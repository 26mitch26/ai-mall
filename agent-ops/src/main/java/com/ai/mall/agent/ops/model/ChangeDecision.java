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
public class ChangeDecision {
    private String id;
    private String alertId;
    private String healActionId;
    private double riskScore;
    private String approver;
    private String status;
    private LocalDateTime timestamp;
    private String reason;
}
