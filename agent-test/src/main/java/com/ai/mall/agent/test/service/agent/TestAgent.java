package com.ai.mall.agent.test.service.agent;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.config.OpenApiConfig;
import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.TestCase;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import com.ai.mall.agent.test.service.generator.AiTestCaseGenerator;
import com.ai.mall.agent.test.service.generator.TestCaseGenerator;
import com.ai.mall.agent.test.service.report.TestReportGenerator;
import com.ai.mall.agent.test.service.report.TestReportStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestAgent {

    private final TestCaseGenerator testCaseGenerator;
    private final AiTestCaseGenerator aiTestCaseGenerator;
    private final TestExecutor testExecutor;
    private final TestReportGenerator reportGenerator;
    private final TestReportStore reportStore;
    private final OpenApiConfig openApiConfig;
    private final AgentTestConfig config;

    // Thread-safe tracking of running test sessions
    private final ConcurrentMap<String, Thread> runningSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> moduleLocks = new ConcurrentHashMap<>();

    /**
     * Run tests for a given module. Each invocation uses a unique session ID for thread safety.
     * Multiple concurrent invocations with different modules are supported.
     *
     * @param moduleName the module to test
     * @return the test report
     */
    public TestReport runTests(String moduleName) {
        String sessionId = generateSessionId(moduleName);
        log.info("TestAgent starting test session [{}] for module: {}", sessionId, moduleName);

        // Track this session
        Thread currentThread = Thread.currentThread();
        runningSessions.put(sessionId, currentThread);

        try {
            // Discover API definitions via OpenAPI
            List<ApiDefinition> apis = openApiConfig.fetchApiDefinitions();

            // If no APIs discovered (e.g., target offline), use fallback
            if (apis.isEmpty()) {
            log.warn("No APIs discovered via OpenAPI. Using fallback definitions.");
            apis = openApiConfig.getFallbackApiDefinitions();
        }

            log.info("Discovered {} API definitions for module: {}", apis.size(), moduleName);

            List<TestCase> allTestCases = new ArrayList<>();

            for (ApiDefinition api : apis) {
                List<TestCase> testCases;
                if (config.getAi().isEnabled()) {
                    log.debug("Using AI test case generator for API: {} {}", api.getMethod(), api.getPath());
                    testCases = aiTestCaseGenerator.generateTestCases(api);
                } else {
                    testCases = testCaseGenerator.generateTestCases(api);
                }

                if (testCases != null) {
                    allTestCases.addAll(testCases);
                }
            }

            log.info("Generated {} test cases for session [{}]", allTestCases.size(), sessionId);

            List<TestResult> results = new ArrayList<>();
            for (TestCase testCase : allTestCases) {
                // Check if this session was interrupted
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Test session [{}] was interrupted", sessionId);
                    break;
                }

                TestResult result = testExecutor.execute(testCase);
                results.add(result);

                log.debug("Session [{}] - Test case {}: {}",
                        sessionId, testCase.getName(), result.isPassed() ? "PASSED" : "FAILED");
            }

            TestReport report = reportGenerator.generateReport(moduleName, results);

            // Persist the report
            reportStore.save(report);

            log.info("Test session [{}] completed. Report ID: {}, {} tests, {} passed, {} failed",
                    sessionId, report.getId(), report.getTotalTests(),
                    report.getPassedTests(), report.getFailedTests());

            return report;

        } catch (Exception e) {
            log.error("Test session [{}] failed with error: {}", sessionId, e.getMessage(), e);

            // Create an error report
            TestReport errorReport = reportGenerator.generateReport(moduleName, List.of(
                    TestResult.builder()
                            .testCaseId("session-error")
                            .testCaseName("Session Error")
                            .passed(false)
                            .actualStatusCode(500)
                            .errorMessage("Test session failed: " + e.getMessage())
                            .executionTime(0)
                            .build()
            ));

            reportStore.save(errorReport);
            return errorReport;

        } finally {
            runningSessions.remove(sessionId);
            log.debug("Cleaned up session [{}]", sessionId);
        }
    }

    /**
     * Generate a unique session ID for a test run.
     */
    private String generateSessionId(String moduleName) {
        return moduleName + "-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }
}