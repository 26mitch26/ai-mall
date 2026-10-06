package com.ai.mall.agent.test.service.agent;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.config.OpenApiConfig;
import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.TestCase;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import com.ai.mall.agent.test.model.EnvironmentCheck;
import com.ai.mall.agent.test.service.generator.AiTestCaseGenerator;
import com.ai.mall.agent.test.service.generator.TestCaseGenerator;
import com.ai.mall.agent.test.service.insight.FailureClassifier;
import com.ai.mall.agent.test.service.insight.TestInsightStore;
import com.ai.mall.agent.test.service.probe.TargetHealthProbe;
import com.ai.mall.agent.test.service.quality.AgentQualityEvaluationRunner;
import com.ai.mall.agent.test.service.report.TestReportGenerator;
import com.ai.mall.agent.test.service.report.TestReportStore;
import com.ai.mall.agent.test.service.scenario.CustomerScenarioRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

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
    @Autowired(required = false)
    private AiTestCaseGenerator aiTestCaseGenerator;
    private final TestExecutor testExecutor;
    private final TestReportGenerator reportGenerator;
    private final TestReportStore reportStore;
    private final OpenApiConfig openApiConfig;
    private final AgentTestConfig config;
    private final FailureClassifier failureClassifier;
    private final TestInsightStore insightStore;
    private final CustomerScenarioRunner customerScenarioRunner;
    private final TargetHealthProbe targetHealthProbe;
    private final AgentQualityEvaluationRunner qualityEvaluationRunner;

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
            // 客服会话场景套件：不走 OpenAPI 契约流程，直接执行"对话意图→工具取数→事实约束→安全边界"用例。
            // 契约用例验证接口存在与参数行为，场景用例验证会话能力——两者互补，缺一都会漏测
            //（"我买了哪些东西答不出来"正是契约层绿、会话层红的案例，见实验报告 Failure Case 09）。
            if (customerScenarioRunner.supports(moduleName) || qualityEvaluationRunner.supports(moduleName)) {
                EnvironmentCheck scenarioEnv = targetHealthProbe.probe(moduleName,
                        config.getScenario().getGatewayUrl());
                List<TestResult> scenarioResults = customerScenarioRunner.supports(moduleName)
                        ? customerScenarioRunner.run()
                        : qualityEvaluationRunner.run();
                // 会话层缺陷同样回流：此前只有契约分支进经验库，导致"契约绿、会话红"的问题
                // （如"我买了哪些东西答不出来"）无法被经验库记住，见实验报告 Failure Case 09
                recordConversationalDefects(scenarioResults);
                TestReport scenarioReport = reportGenerator.generateReport(moduleName, scenarioResults);
                scenarioReport.setEnvironment(scenarioEnv);
                scenarioReport.setKnownDefects(insightStore.snapshot());
                reportStore.save(scenarioReport);
                // 必须在 save 之后：markRoundCompleted 会清掉"本轮命中"标记，
                // 而报告需要保留本轮命中的缺陷，才能正确标注"第 N 次复现"
                insightStore.markRoundCompleted();
                log.info("Conversational suite [{}] completed. Report ID: {}, {} tests, {} passed, {} failed",
                        sessionId, scenarioReport.getId(), scenarioReport.getTotalTests(),
                        scenarioReport.getPassedTests(), scenarioReport.getFailedTests());
                return scenarioReport;
            }

            // Discover API definitions via OpenAPI
            List<ApiDefinition> apis = openApiConfig.fetchApiDefinitions(moduleName);

            // If no APIs discovered (e.g., target offline), use fallback
            if (apis.isEmpty()) {
            log.warn("No APIs discovered via OpenAPI. Using fallback definitions.");
            apis = openApiConfig.getFallbackApiDefinitions();
        }

            if (config.isSafeDemoMode()) {
                apis = apis.stream()
                        .filter(api -> "GET".equalsIgnoreCase(api.getMethod()))
                        .filter(api -> isSafeDemoApi(moduleName, api.getPath()))
                        .limit(config.getMaxApisPerRun())
                        .toList();
                log.info("Safe demo mode selected {} read-only APIs", apis.size());
            }

            log.info("Discovered {} API definitions for module: {}", apis.size(), moduleName);

            List<TestCase> allTestCases = new ArrayList<>();

            for (ApiDefinition api : apis) {
                List<TestCase> testCases;
                if (config.getAi().isEnabled() && aiTestCaseGenerator != null) {
                    log.debug("Using AI test case generator for API: {} {}", api.getMethod(), api.getPath());
                    // AI 探索性用例（经契约守卫）+ 规则三件套（正常/异常/边界，契约对齐后），
                    // 兼顾 LLM 的探索广度与规则模板的确定性覆盖
                    testCases = new ArrayList<>(aiTestCaseGenerator.generateTestCases(api));
                    testCases.addAll(aiTestCaseGenerator.generateNormalCases(api));
                    testCases.addAll(aiTestCaseGenerator.generateErrorCases(api));
                    testCases.addAll(aiTestCaseGenerator.generateBoundaryCases(api));
                } else {
                    testCases = testCaseGenerator.generateTestCases(api);
                }

                if (testCases != null) {
                    allTestCases.addAll(testCases);
                }
            }

            log.info("Generated {} test cases for session [{}]", allTestCases.size(), sessionId);

            List<TestResult> results = new ArrayList<>();
            String executionBaseUrl = config.getModuleBaseUrls() == null
                    ? config.getBaseUrl()
                    : config.getModuleBaseUrls().getOrDefault(moduleName, config.getBaseUrl());

            // 开跑前先探一次环境：被测服务没起来时，报告必须能自证"红灯来自环境而非代码"
            EnvironmentCheck environment = targetHealthProbe.probe(moduleName, executionBaseUrl);
            if (!environment.isReachable() && !environment.isSkipped()) {
                log.warn("Target [{}] is not reachable ({}). Failures in this run are likely environmental.",
                        executionBaseUrl, environment.getDetail());
            }

            for (TestCase testCase : allTestCases) {
                // Check if this session was interrupted
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Test session [{}] was interrupted", sessionId);
                    break;
                }

                TestResult result = testExecutor.execute(testCase, executionBaseUrl, moduleName);
                results.add(result);

                // 失败回流：高价值失败信号（语义断言失败/5xx）沉淀为已知缺陷
                if (!result.isPassed()) {
                    failureClassifier.classify(testCase, result)
                            .ifPresent(summary -> {
                                log.info("Insight recorded for {} {}: {}",
                                        testCase.getMethod(), testCase.getApiPath(), summary);
                                insightStore.record(testCase.getMethod(), testCase.getApiPath(), summary);
                            });
                }

                log.debug("Session [{}] - Test case {}: {}",
                        sessionId, testCase.getName(), result.isPassed() ? "PASSED" : "FAILED");
            }

            TestReport report = reportGenerator.generateReport(moduleName, results);
            report.setEnvironment(environment);

            // 挂载历史已知缺陷：报告中标注"第 N 次复现"，让回归站在历史经验上
            report.setKnownDefects(insightStore.snapshot());
            reportStore.save(report);
            // 必须在 save 之后：markRoundCompleted 会清掉"本轮命中"标记，
            // 而报告需要保留本轮命中的缺陷，才能正确标注"第 N 次复现"
            insightStore.markRoundCompleted();

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

    private boolean isSafeDemoApi(String moduleName, String path) {
        if (!"mall-portal".equals(moduleName)) {
            return true;
        }
        return path.startsWith("/home/") || path.startsWith("/product/") || path.startsWith("/brand/");
    }

    /**
     * 会话/评测类结果的失败回流。
     *
     * <p>按 {@code POST /chat#用例ID} 聚合而不是只按端点聚合：场景套件里多条用例打的是
     * 同一个端点，按端点聚合会把不同用例的失败原因互相覆盖，丢掉可定位性。
     */
    private void recordConversationalDefects(List<TestResult> results) {
        for (TestResult result : results) {
            failureClassifier.classifyConversational(result).ifPresent(summary -> {
                String key = CustomerScenarioRunner.CHAT_PATH + "#" + result.getTestCaseId();
                log.info("Conversational insight recorded [{}]: {}", key, summary);
                insightStore.record("POST", key, summary);
            });
        }
    }
}
