package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** Deterministic, offline comparison of two persisted test reports. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestReportComparison {

    private String baselineReportId;
    private String currentReportId;
    /** False when the reports cannot support a trustworthy business regression comparison. */
    private boolean comparable;
    private List<String> warnings;
    private Map<String, Integer> counts;
    private List<Entry> entries;

    public enum Category {
        NEW_FAILURE,
        FIXED,
        PERSISTING_FAILURE,
        NEW_CASE,
        REMOVED_CASE,
        ENVIRONMENT_UNAVAILABLE,
        UNVERIFIED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private Category category;
        private String caseKey;
        private String testCaseName;
        private Boolean baselinePassed;
        private Boolean currentPassed;
        private Integer baselineStatusCode;
        private Integer currentStatusCode;
        private String baselineError;
        private String currentError;
    }
}
