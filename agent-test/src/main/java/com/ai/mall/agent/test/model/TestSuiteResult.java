package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteResult {

    private String suiteId;
    private String suiteName;
    private String description;

    private int totalTests;
    private int passedTests;
    private int failedTests;
    private int skippedTests;

    private double passRate;
    private long totalExecutionTime;
    private double averageResponseTime;

    private Map<String, Long> scenarioCoverage;
    private Map<String, String> parameterCoverage;
    private Map<String, String> responseCodeCoverage;

    private List<TestResult> results;
    private List<TestReport> reports;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
