package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestReport {
    private String id;
    private String moduleName;
    private int totalTests;
    private int passedTests;
    private int failedTests;
    private double passRate;
    private double averageResponseTime;
    private long totalExecutionTime;
    private int assertionsTotal;
    private int assertionsPassed;
    private int assertionsFailed;
    private List<TestResult> results;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}