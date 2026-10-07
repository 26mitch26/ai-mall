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
public class TestResult {
    private String testCaseId;
    private String testCaseName;
    /** Stable contract identity; legacy reports may omit these fields. */
    private String method;
    private String apiPath;
    private boolean passed;
    private int actualStatusCode;
    private String actualResponse;
    private long executionTime;
    private String errorMessage;
    private LocalDateTime timestamp;
    private List<AssertionDetail> assertionDetails;
}
