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
public class AlertEvent {
    private String id;
    private String metricName;
    private double metricValue;
    private String targetService;
    private Severity severity;
    private LocalDateTime timestamp;
    private String status;
}
