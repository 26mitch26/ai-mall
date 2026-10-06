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
    private List<KnownDefect> knownDefects;
    /**
     * 开跑前的环境可达性结论。
     *
     * <p>没有它，被测服务没启动时报告就是满屏红灯，使用者得先自己分辨
     * "环境挂了"还是"代码坏了"；有了它，红灯有了明确归因。
     */
    private EnvironmentCheck environment;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}