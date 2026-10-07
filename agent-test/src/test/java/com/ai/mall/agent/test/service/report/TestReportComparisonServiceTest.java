package com.ai.mall.agent.test.service.report;

import com.ai.mall.agent.test.model.EnvironmentCheck;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestReportComparison;
import com.ai.mall.agent.test.model.TestReportComparison.Category;
import com.ai.mall.agent.test.model.TestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestReportComparisonServiceTest {

    private TestReportStore store;
    private TestReportComparisonService service;

    @BeforeEach
    void setUp() {
        store = mock(TestReportStore.class);
        service = new TestReportComparisonService(store);
    }

    @Test
    void classifiesRegressionsFixesPersistentFailuresAndCaseChurnDeterministically() {
        TestReport baseline = report("baseline", true,
                result("random-old-1", "GET", "/a", "regression", true, 200, null),
                result("random-old-2", "POST", "/b", "fixed", false, 500, "old failure"),
                result("random-old-3", "GET", "/c", "still broken", false, 500, "still failing"),
                result("random-old-4", "GET", "/removed", "removed", true, 200, null));
        TestReport current = report("current", true,
                result("random-new-1", "get", "/a", "regression", false, 500, "new failure"),
                result("random-new-2", "POST", "/b", "fixed", true, 200, null),
                result("random-new-3", "GET", "/c", "still broken", false, 500, "still failing"),
                result("random-new-4", "PATCH", "/new", "new", true, 201, null));
        save(baseline, current);

        TestReportComparison comparison = service.compare("baseline", "current");

        assertTrue(comparison.isComparable());
        assertEquals("baseline", comparison.getBaselineReportId());
        assertEquals("current", comparison.getCurrentReportId());
        assertEquals(1, comparison.getCounts().get(Category.NEW_FAILURE.name()));
        assertEquals(1, comparison.getCounts().get(Category.FIXED.name()));
        assertEquals(1, comparison.getCounts().get(Category.PERSISTING_FAILURE.name()));
        assertEquals(1, comparison.getCounts().get(Category.NEW_CASE.name()));
        assertEquals(1, comparison.getCounts().get(Category.REMOVED_CASE.name()));
        assertEquals(5, comparison.getEntries().size());
        assertEquals(Category.NEW_FAILURE, comparison.getEntries().stream()
                .filter(entry -> "regression".equals(entry.getTestCaseName())).findFirst().orElseThrow().getCategory());
    }

    @Test
    void usesStableMethodPathAndNameInsteadOfRandomCaseIds() {
        save(report("baseline", true,
                        result("uuid-one", "get", "/items/{id}", "read item", true, 200, null)),
                report("current", true,
                        result("uuid-two", " GET ", "/items/{id}", "read item", false, 500, "failure")));

        TestReportComparison comparison = service.compare("baseline", "current");

        assertEquals(1, comparison.getEntries().size());
        assertEquals(Category.NEW_FAILURE, comparison.getEntries().get(0).getCategory());
        assertTrue(comparison.getEntries().get(0).getCaseKey().contains("GET /items/{id}"));
    }

    @Test
    void doesNotMatchSameNameWhenBothReportsContainDifferentStableEndpoints() {
        save(report("baseline", true,
                        result("uuid-one", "GET", "/old", "same label", true, 200, null)),
                report("current", true,
                        result("uuid-two", "GET", "/new", "same label", true, 200, null)));

        TestReportComparison comparison = service.compare("baseline", "current");

        assertEquals(1, comparison.getCounts().get(Category.NEW_CASE.name()));
        assertEquals(1, comparison.getCounts().get(Category.REMOVED_CASE.name()));
    }

    @Test
    void reportsUnreachableOrUnverifiedEnvironmentAsNotComparable() {
        TestReport baseline = report("baseline", true,
                result("a", "GET", "/a", "case", true, 200, null));
        TestReport current = report("current", false,
                result("b", "GET", "/a", "case", false, 0, "connection refused"));
        save(baseline, current);

        TestReportComparison comparison = service.compare("baseline", "current");

        assertFalse(comparison.isComparable());
        assertTrue(comparison.getEntries().isEmpty());
        assertEquals(0, comparison.getCounts().get(Category.NEW_FAILURE.name()));
        assertTrue(comparison.getWarnings().stream().anyMatch(warning -> warning.contains("unreachable")));
    }

    @Test
    void classifiesPerCaseConnectionFailureAsEnvironmentUnavailable() {
        save(report("baseline", true,
                        result("a", "GET", "/a", "case", true, 200, null)),
                report("current", true,
                        result("b", "GET", "/a", "case", false, 0, "connection reset")));

        TestReportComparison comparison = service.compare("baseline", "current");

        assertTrue(comparison.isComparable());
        assertEquals(1, comparison.getCounts().get(Category.ENVIRONMENT_UNAVAILABLE.name()));
        assertEquals(0, comparison.getCounts().get(Category.NEW_FAILURE.name()));
        assertTrue(comparison.getWarnings().stream().anyMatch(warning -> warning.contains("connection failure")));
    }

    @Test
    void legacyReportsMatchOnlyByUniqueNameAndExplainFallback() {
        save(report("baseline", true,
                        result("old-id", null, null, "same case", true, 200, null)),
                report("current", true,
                        result("new-id", "GET", "/a", "same case", false, 500, "regression")));

        TestReportComparison comparison = service.compare("baseline", "current");

        assertEquals(1, comparison.getCounts().get(Category.NEW_FAILURE.name()));
        assertTrue(comparison.getWarnings().stream().anyMatch(warning -> warning.contains("unique module+name fallback")));
    }

    @Test
    void rejectsDuplicateIdentityAndBlankNamesInsteadOfOverwriting() {
        TestReport duplicate = report("baseline", true,
                result("a", "GET", "/a", "duplicate", true, 200, null),
                result("b", "get", "/a", "duplicate", false, 500, "second"));
        when(store.findById("baseline")).thenReturn(duplicate);
        when(store.findById("current")).thenReturn(report("current", true));
        IllegalArgumentException duplicateError = assertThrows(IllegalArgumentException.class,
                () -> service.compare("baseline", "current"));
        assertTrue(duplicateError.getMessage().contains("duplicate case identity"));

        when(store.findById("baseline")).thenReturn(report("baseline", true,
                result("a", "GET", "/a", "   ", true, 200, null)));
        IllegalArgumentException blankError = assertThrows(IllegalArgumentException.class,
                () -> service.compare("baseline", "current"));
        assertTrue(blankError.getMessage().contains("blank testCaseName"));
    }

    @Test
    void rejectsMissingBaselineClearlyAndDoesNotTreatItAsAnEmptyPassingReport() {
        when(store.findById("current")).thenReturn(report("current", true));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.compare("missing-baseline", "current"));

        assertTrue(error.getMessage().contains("baseline report not found: missing-baseline"));
    }

    @Test
    void rejectsAmbiguousLegacyNameFallback() {
        TestReport baseline = report("baseline", true,
                result("old", null, null, "same name", true, 200, null));
        TestReport current = report("current", true,
                result("new-1", "GET", "/a", "same name", true, 200, null),
                result("new-2", "POST", "/b", "same name", true, 200, null));
        save(baseline, current);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.compare("baseline", "current"));

        assertTrue(error.getMessage().contains("name must be unique across both complete reports"));
    }

    @Test
    void rejectsSameReportAndDifferentOrBlankModules() {
        TestReport baseline = report("baseline", true);
        TestReport current = report("current", true);
        save(baseline, current);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.compare("baseline", "baseline")).getMessage().contains("must be different"));

        TestReport otherModule = TestReport.builder().id("current").moduleName("orders")
                .environment(baseline.getEnvironment()).results(List.of()).build();
        when(store.findById("current")).thenReturn(otherModule);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.compare("baseline", "current")).getMessage().contains("different modules"));

        TestReport blankModule = TestReport.builder().id("current").moduleName("  ")
                .environment(baseline.getEnvironment()).results(List.of()).build();
        when(store.findById("current")).thenReturn(blankModule);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.compare("baseline", "current")).getMessage().contains("non-blank moduleName"));
    }

    @Test
    void allowsMultipleCompleteCasesWithSameNameAcrossDifferentEndpoints() {
        TestReport baseline = report("baseline", true,
                result("a1", "GET", "/a", "same name", true, 200, null),
                result("a2", "POST", "/b", "same name", true, 200, null));
        TestReport current = report("current", true,
                result("b1", "GET", "/a", "same name", true, 200, null),
                result("b2", "PUT", "/c", "same name", true, 200, null));
        save(baseline, current);

        TestReportComparison comparison = service.compare("baseline", "current");

        assertEquals(1, comparison.getCounts().get(Category.NEW_CASE.name()));
        assertEquals(1, comparison.getCounts().get(Category.REMOVED_CASE.name()));
    }

    @Test
    void legacyFallbackChecksNameUniquenessAcrossAlreadyMatchedCasesToo() {
        TestReport baseline = report("baseline", true,
                result("a1", "GET", "/a", "same name", true, 200, null),
                result("a2", null, null, "same name", true, 200, null));
        TestReport current = report("current", true,
                result("b1", "GET", "/a", "same name", true, 200, null),
                result("b2", "PUT", "/b", "same name", true, 200, null));
        save(baseline, current);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.compare("baseline", "current"));

        assertTrue(error.getMessage().contains("unique across both complete reports"));
    }

    @Test
    void withholdsUnknownNoStatusFailureAsUnverified() {
        save(report("baseline", true,
                        result("a", "GET", "/a", "case", true, 200, null)),
                report("current", true,
                        result("b", "GET", "/a", "case", false, 0, "unexpected execution error")));

        TestReportComparison comparison = service.compare("baseline", "current");

        assertEquals(1, comparison.getCounts().get(Category.UNVERIFIED.name()));
        assertEquals(0, comparison.getCounts().get(Category.NEW_FAILURE.name()));
        assertTrue(comparison.getWarnings().stream().anyMatch(warning -> warning.contains("unverified failure cause")));
    }

    @Test
    void emptyOrMissingResultsCannotBecomeGreenBaseline() {
        save(report("baseline", true), report("current", true,
                result("b", "GET", "/a", "case", true, 200, null)));
        assertFalse(service.compare("baseline", "current").isComparable());
        TestReport missingDetails = report("baseline", true);
        missingDetails.setResults(null);
        when(store.findById("baseline")).thenReturn(missingDetails);
        assertThrows(IllegalArgumentException.class, () -> service.compare("baseline", "current"));
    }

    private void save(TestReport baseline, TestReport current) {
        when(store.findById(baseline.getId())).thenReturn(baseline);
        when(store.findById(current.getId())).thenReturn(current);
    }

    private TestReport report(String id, boolean reachable, TestResult... results) {
        return TestReport.builder().id(id).moduleName("catalog")
                .environment(EnvironmentCheck.builder().module("catalog").target("http://localhost")
                        .reachable(reachable).skipped(false).build())
                .results(new ArrayList<>(List.of(results))).build();
    }

    private TestResult result(String id, String method, String path, String name,
                              boolean passed, int status, String error) {
        return TestResult.builder().testCaseId(id).method(method).apiPath(path).testCaseName(name)
                .passed(passed).actualStatusCode(status).errorMessage(error).build();
    }
}
