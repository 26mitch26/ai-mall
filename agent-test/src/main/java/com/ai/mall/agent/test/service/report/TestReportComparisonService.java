package com.ai.mall.agent.test.service.report;

import com.ai.mall.agent.test.model.EnvironmentCheck;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestReportComparison;
import com.ai.mall.agent.test.model.TestReportComparison.Category;
import com.ai.mall.agent.test.model.TestResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Compares retained reports without network, database, or AI calls. */
@Service
@RequiredArgsConstructor
public class TestReportComparisonService {

    private final TestReportStore reportStore;

    public TestReportComparison compare(String baselineReportId, String currentReportId) {
        if (baselineReportId != null && baselineReportId.equals(currentReportId)) {
            throw new IllegalArgumentException("baselineReportId and currentReportId must be different");
        }
        TestReport baseline = requireReport(baselineReportId, "baseline");
        TestReport current = requireReport(currentReportId, "current");
        String baselineModule = normalize(baseline.getModuleName());
        String currentModule = normalize(current.getModuleName());
        if (baselineModule.isEmpty() || currentModule.isEmpty()) {
            throw new IllegalArgumentException("Both reports must have a non-blank moduleName");
        }
        if (!baselineModule.equals(currentModule)) {
            throw new IllegalArgumentException("Cannot compare reports from different modules: "
                    + baselineModule + " and " + currentModule);
        }
        List<String> warnings = new ArrayList<>();
        Map<CaseIdentity, TestResult> baselineCases = index(baseline, "baseline", warnings);
        Map<CaseIdentity, TestResult> currentCases = index(current, "current", warnings);
        if (baselineCases.isEmpty() || currentCases.isEmpty()) {
            warnings.add("One report has no executed test cases; an empty suite cannot establish a regression baseline");
            return result(baselineReportId, currentReportId, false, warnings, List.of());
        }

        boolean baselineEnvironmentUsable = environmentSupportsComparison(baseline, "baseline", warnings);
        boolean currentEnvironmentUsable = environmentSupportsComparison(current, "current", warnings);
        if (!baselineEnvironmentUsable || !currentEnvironmentUsable) {
            return result(baselineReportId, currentReportId, false, warnings, List.of());
        }

        List<TestReportComparison.Entry> entries = new ArrayList<>();
        Set<CaseIdentity> matchedBaseline = new LinkedHashSet<>();
        Set<CaseIdentity> matchedCurrent = new LinkedHashSet<>();
        for (CaseIdentity identity : baselineCases.keySet()) {
            if (!identity.complete()) continue;
            TestResult currentResult = currentCases.get(identity);
            if (currentResult != null) {
                addMatchedEntry(entries, identity, baselineCases.get(identity), currentResult);
                matchedBaseline.add(identity);
                matchedCurrent.add(identity);
            }
        }
        matchLegacy(baselineCases, currentCases, matchedBaseline, matchedCurrent, entries, warnings);

        for (Map.Entry<CaseIdentity, TestResult> item : baselineCases.entrySet()) {
            if (!matchedBaseline.contains(item.getKey())) {
                TestResult value = item.getValue();
                entries.add(entry(noHttpOutcomeCategory(value, Category.REMOVED_CASE), item.getKey(), value, null));
            }
        }
        for (Map.Entry<CaseIdentity, TestResult> item : currentCases.entrySet()) {
            if (!matchedCurrent.contains(item.getKey())) {
                TestResult value = item.getValue();
                entries.add(entry(noHttpOutcomeCategory(value, Category.NEW_CASE), item.getKey(), null, value));
            }
        }
        entries.sort(Comparator.comparing((TestReportComparison.Entry e) -> e.getCategory().name())
                .thenComparing(TestReportComparison.Entry::getCaseKey));
        return result(baselineReportId, currentReportId, true, warnings, entries);
    }

    private TestReport requireReport(String reportId, String role) {
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException(role + " reportId must not be blank");
        }
        TestReport report = reportStore.findById(reportId);
        if (report == null) throw new IllegalArgumentException(role + " report not found: " + reportId);
        return report;
    }

    private Map<CaseIdentity, TestResult> index(TestReport report, String role, List<String> warnings) {
        Map<CaseIdentity, TestResult> indexed = new TreeMap<>(Comparator
                .comparing(CaseIdentity::module).thenComparing(CaseIdentity::method)
                .thenComparing(CaseIdentity::apiPath).thenComparing(CaseIdentity::name));
        if (report.getResults() == null) {
            throw new IllegalArgumentException(role + " report has no result details");
        }
        for (int i = 0; i < report.getResults().size(); i++) {
            TestResult result = report.getResults().get(i);
            if (result == null) {
                throw new IllegalArgumentException(role + " report " + report.getId()
                        + " contains null result at index " + i);
            }
            String name = normalize(result.getTestCaseName());
            if (name.isEmpty()) {
                throw new IllegalArgumentException(role + " report " + report.getId()
                        + " contains blank testCaseName at index " + i);
            }
            String module = normalize(report.getModuleName());
            String method = normalize(result.getMethod()).toUpperCase(Locale.ROOT);
            String apiPath = normalize(result.getApiPath());
            boolean complete = !method.isEmpty() && !apiPath.isEmpty();
            if (!complete) {
                warnings.add(role + " report " + report.getId() + " case '" + name
                        + "' uses legacy module+name identity because method/apiPath is missing");
            }
            CaseIdentity identity = new CaseIdentity(module, complete ? method : "",
                    complete ? apiPath : "", name, complete);
            if (indexed.putIfAbsent(identity, result) != null) {
                throw new IllegalArgumentException(role + " report " + report.getId()
                        + " contains duplicate case identity: " + identity.display());
            }
        }
        return indexed;
    }

    private void matchLegacy(Map<CaseIdentity, TestResult> baselineCases,
                             Map<CaseIdentity, TestResult> currentCases,
                             Set<CaseIdentity> matchedBaseline, Set<CaseIdentity> matchedCurrent,
                             List<TestReportComparison.Entry> entries, List<String> warnings) {
        Map<LegacyIdentity, List<CaseIdentity>> leftAll = byName(baselineCases);
        Map<LegacyIdentity, List<CaseIdentity>> rightAll = byName(currentCases);
        Set<LegacyIdentity> names = new LinkedHashSet<>(leftAll.keySet());
        names.retainAll(rightAll.keySet());
        for (LegacyIdentity name : names) {
            List<CaseIdentity> left = leftAll.get(name);
            List<CaseIdentity> right = rightAll.get(name);
            List<CaseIdentity> leftRemaining = left.stream().filter(key -> !matchedBaseline.contains(key)).toList();
            List<CaseIdentity> rightRemaining = right.stream().filter(key -> !matchedCurrent.contains(key)).toList();
            boolean legacyFallbackNeeded = leftRemaining.stream().anyMatch(key -> !key.complete())
                    || rightRemaining.stream().anyMatch(key -> !key.complete());
            if (!legacyFallbackNeeded) continue;
            if (left.size() != 1 || right.size() != 1) {
                throw new IllegalArgumentException("Cannot safely match case name '" + name.name()
                        + "' in module '" + name.module()
                        + "': name must be unique across both complete reports when method/apiPath is missing");
            }
            if (leftRemaining.isEmpty() || rightRemaining.isEmpty()) {
                throw new IllegalArgumentException("Cannot safely match legacy case name '" + name.name()
                        + "' because its counterpart already matched another case");
            }
            CaseIdentity leftKey = left.get(0);
            CaseIdentity rightKey = right.get(0);
            if (matchedBaseline.contains(leftKey) || matchedCurrent.contains(rightKey)) {
                throw new IllegalArgumentException("Cannot safely match legacy case name '" + name.name()
                        + "' because its counterpart already matched another case");
            }
            warnings.add("Matched case '" + name.name()
                    + "' by unique module+name fallback because method/apiPath is missing in one report");
            addMatchedEntry(entries, leftKey, baselineCases.get(leftKey), currentCases.get(rightKey));
            matchedBaseline.add(leftKey);
            matchedCurrent.add(rightKey);
        }
    }

    private Map<LegacyIdentity, List<CaseIdentity>> byName(Map<CaseIdentity, TestResult> cases) {
        Map<LegacyIdentity, List<CaseIdentity>> grouped = new LinkedHashMap<>();
        cases.keySet().forEach(key ->
                grouped.computeIfAbsent(new LegacyIdentity(key.module(), key.name()), ignored -> new ArrayList<>())
                        .add(key));
        return grouped;
    }

    private void addMatchedEntry(List<TestReportComparison.Entry> entries, CaseIdentity identity,
                                 TestResult baseline, TestResult current) {
        Category category;
        if (caseEnvironmentUnavailable(baseline) || caseEnvironmentUnavailable(current)) {
            category = Category.ENVIRONMENT_UNAVAILABLE;
        } else if (hasNoHttpOutcome(baseline) || hasNoHttpOutcome(current)) {
            category = Category.UNVERIFIED;
        } else if (baseline.isPassed() && !current.isPassed()) {
            category = Category.NEW_FAILURE;
        } else if (!baseline.isPassed() && current.isPassed()) {
            category = Category.FIXED;
        } else if (!baseline.isPassed()) {
            category = Category.PERSISTING_FAILURE;
        } else {
            return;
        }
        entries.add(entry(category, identity, baseline, current));
    }

    private TestReportComparison.Entry entry(Category category, CaseIdentity identity,
                                             TestResult baseline, TestResult current) {
        return TestReportComparison.Entry.builder().category(category).caseKey(identity.display())
                .testCaseName(identity.name())
                .baselinePassed(baseline == null ? null : baseline.isPassed())
                .currentPassed(current == null ? null : current.isPassed())
                .baselineStatusCode(baseline == null ? null : baseline.getActualStatusCode())
                .currentStatusCode(current == null ? null : current.getActualStatusCode())
                .baselineError(baseline == null ? null : baseline.getErrorMessage())
                .currentError(current == null ? null : current.getErrorMessage()).build();
    }

    private boolean caseEnvironmentUnavailable(TestResult result) {
        if (result == null || result.getActualStatusCode() != 0) return false;
        if (result.getAssertionDetails() != null && result.getAssertionDetails().stream()
                .anyMatch(assertion -> assertion != null && "Connection Check".equalsIgnoreCase(
                        normalize(assertion.getAssertionName())))) return true;
        String error = normalize(result.getErrorMessage()).toLowerCase(Locale.ROOT);
        return error.contains("connection refused") || error.contains("connection reset")
                || error.contains("connect timed out") || error.contains("connection timed out")
                || error.contains("failed to connect") || error.contains("unable to connect")
                || error.contains("no route to host") || error.contains("unknown host");
    }

    private boolean hasNoHttpOutcome(TestResult result) {
        return result != null && result.getActualStatusCode() == 0;
    }

    private Category noHttpOutcomeCategory(TestResult result, Category otherwise) {
        if (caseEnvironmentUnavailable(result)) return Category.ENVIRONMENT_UNAVAILABLE;
        if (hasNoHttpOutcome(result)) return Category.UNVERIFIED;
        return otherwise;
    }

    private boolean environmentSupportsComparison(TestReport report, String role, List<String> warnings) {
        EnvironmentCheck environment = report.getEnvironment();
        if (environment == null) {
            warnings.add(role + " report " + report.getId()
                    + " has no environment check; comparison is unavailable");
            return false;
        }
        if (environment.isSkipped()) {
            warnings.add(role + " report " + report.getId()
                    + " skipped the environment check; comparison is unavailable");
            return false;
        }
        if (!environment.isReachable()) {
            warnings.add(role + " report " + report.getId()
                    + " target is unreachable; no business regression is inferred");
            return false;
        }
        return true;
    }

    private TestReportComparison result(String baselineId, String currentId, boolean comparable,
                                        List<String> warnings, List<TestReportComparison.Entry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Category category : Category.values()) counts.put(category.name(), 0);
        for (TestReportComparison.Entry entry : entries) {
            counts.compute(entry.getCategory().name(), (ignored, value) -> value + 1);
            if (entry.getCategory() == Category.ENVIRONMENT_UNAVAILABLE) {
                warnings.add("Case '" + entry.getTestCaseName()
                        + "' has a connection failure and was withheld from business regression classification");
            } else if (entry.getCategory() == Category.UNVERIFIED) {
                warnings.add("Case '" + entry.getTestCaseName()
                        + "' has no HTTP status and an unverified failure cause; no business regression is inferred");
            }
        }
        return TestReportComparison.builder().baselineReportId(baselineId).currentReportId(currentId)
                .comparable(comparable).warnings(List.copyOf(warnings))
                .counts(Collections.unmodifiableMap(new LinkedHashMap<>(counts)))
                .entries(List.copyOf(entries)).build();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record CaseIdentity(String module, String method, String apiPath, String name, boolean complete) {
        private String display() {
            return complete ? module + " | " + method + " " + apiPath + " | " + name
                    : module + " | legacy | " + name;
        }
    }

    private record LegacyIdentity(String module, String name) { }
}
