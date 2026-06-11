package com.ai.mall.agent.ops.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RCAResult {
    private String alertId;
    private String rootCause;
    private double confidence;
    private List<String> impactChain;
    private List<String> suggestedActions;
}
