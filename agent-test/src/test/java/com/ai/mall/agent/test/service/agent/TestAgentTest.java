package com.ai.mall.agent.test.service.agent;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.config.OpenApiConfig;
import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.EnvironmentCheck;
import com.ai.mall.agent.test.model.KnownDefect;
import com.ai.mall.agent.test.model.TestCase;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import com.ai.mall.agent.test.service.generator.TestCaseGenerator;
import com.ai.mall.agent.test.service.insight.FailureClassifier;
import com.ai.mall.agent.test.service.insight.TestInsightStore;
import com.ai.mall.agent.test.service.probe.TargetHealthProbe;
import com.ai.mall.agent.test.service.quality.AgentQualityEvaluationRunner;
import com.ai.mall.agent.test.service.report.TestReportGenerator;
import com.ai.mall.agent.test.service.report.TestReportStore;
import com.ai.mall.agent.test.service.scenario.CustomerScenarioRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 编排层的关键时序契约：报告必须先落库、再清本轮命中标记，
 * 否则报告里的 hitThisRun 恒为 false（第 N 次复现永远不显示）。
 */
@ExtendWith(MockitoExtension.class)
class TestAgentTest {

    @Mock private TestCaseGenerator testCaseGenerator;
    @Mock private TestExecutor testExecutor;
    @Mock private TestReportGenerator reportGenerator;
    @Mock private TestReportStore reportStore;
    @Mock private OpenApiConfig openApiConfig;
    @Mock private FailureClassifier failureClassifier;
    @Mock private CustomerScenarioRunner customerScenarioRunner;
    @Mock private TargetHealthProbe targetHealthProbe;
    @Mock private AgentQualityEvaluationRunner qualityEvaluationRunner;

    @TempDir
    Path tempDir;

    private TestInsightStore insightStore;
    private TestAgent agent;

    @BeforeEach
    void setUp() {
        AgentTestConfig config = new AgentTestConfig();
        config.setModules(List.of("mall-portal", "agent-customer-scenarios"));
        // 用 spy 而非纯 mock：既要真实缺陷库的聚合/清标记行为，又要能断言调用顺序
        insightStore = spy(new TestInsightStore(new ObjectMapper(), tempDir.resolve("insights.json").toString()));
        insightStore.load();
        agent = new TestAgent(testCaseGenerator, testExecutor, reportGenerator, reportStore,
                openApiConfig, config, failureClassifier, insightStore, customerScenarioRunner,
                targetHealthProbe, qualityEvaluationRunner);
    }

    private void givenReachableTarget() {
        when(targetHealthProbe.probe(anyString(), anyString()))
                .thenReturn(EnvironmentCheck.builder().reachable(true).detail("UP").build());
    }

    @Test
    void reportKeepsHitThisRunAndIsStoredBeforeRoundCompletion() {
        ApiDefinition api = ApiDefinition.builder().method("GET").path("/home/content").build();
        TestCase testCase = TestCase.builder().id("c1").name("normal case")
                .method("GET").apiPath("/home/content").expectedStatusCode(200).build();
        TestResult failed = TestResult.builder().testCaseId("c1")
                .testCaseName("normal case").passed(false).actualStatusCode(500).build();

        when(customerScenarioRunner.supports("mall-portal")).thenReturn(false);
        when(openApiConfig.fetchApiDefinitions("mall-portal")).thenReturn(List.of(api));
        when(testCaseGenerator.generateTestCases(api)).thenReturn(List.of(testCase));
        when(testExecutor.execute(eq(testCase), anyString(), eq("mall-portal"))).thenReturn(failed);
        givenReachableTarget();
        when(failureClassifier.classify(eq(testCase), eq(failed))).thenReturn(Optional.of("suspected defect"));
        when(reportGenerator.generateReport(eq("mall-portal"), anyList()))
                .thenReturn(TestReport.builder().id("r1").moduleName("mall-portal")
                        .totalTests(1).failedTests(1).build());

        TestReport report = agent.runTests("mall-portal");

        assertNotNull(report);
        KnownDefect defect = report.getKnownDefects().get(0);
        assertTrue(defect.isHitThisRun(), "报告必须保留本轮命中标记");
        assertEquals(1, defect.getOccurrences());

        KnownDefect stored = insightStore.findByApi("GET", "/home/content");
        assertNotNull(stored);
        assertFalse(stored.isHitThisRun(), "轮次结束后库内标记应清空，供下一轮重新计数");

        InOrder inOrder = inOrder(reportStore, insightStore);
        inOrder.verify(reportStore).save(report);
        inOrder.verify(insightStore).markRoundCompleted();
    }

    @Test
    void scenarioSuiteStoresReportBeforeMarkingRoundCompleted() {
        TestResult failed = TestResult.builder().testCaseId("s1")
                .testCaseName("order list").passed(false).actualStatusCode(200).build();
        when(customerScenarioRunner.supports("agent-customer-scenarios")).thenReturn(true);
        when(customerScenarioRunner.run()).thenReturn(List.of(failed));
        when(failureClassifier.classifyConversational(failed)).thenReturn(Optional.of("intent mismatch"));
        when(reportGenerator.generateReport(eq("agent-customer-scenarios"), anyList()))
                .thenReturn(TestReport.builder().id("r2").moduleName("agent-customer-scenarios")
                        .totalTests(1).failedTests(1).build());
        givenReachableTarget();

        TestReport report = agent.runTests("agent-customer-scenarios");

        assertNotNull(report);
        InOrder inOrder = inOrder(reportStore, insightStore);
        inOrder.verify(reportStore).save(report);
        inOrder.verify(insightStore).markRoundCompleted();
    }

    @Test
    void scenarioFailuresFlowIntoInsightStore() {
        TestResult failed = TestResult.builder().testCaseId("s1")
                .testCaseName("order list").passed(false).actualStatusCode(200).build();
        when(customerScenarioRunner.supports("agent-customer-scenarios")).thenReturn(true);
        when(customerScenarioRunner.run()).thenReturn(List.of(failed));
        when(failureClassifier.classifyConversational(failed)).thenReturn(Optional.of("intent mismatch"));
        when(reportGenerator.generateReport(eq("agent-customer-scenarios"), anyList()))
                .thenReturn(TestReport.builder().id("r3").moduleName("agent-customer-scenarios").build());
        givenReachableTarget();

        agent.runTests("agent-customer-scenarios");

        KnownDefect defect = insightStore.findByApi("POST",
                CustomerScenarioRunner.CHAT_PATH + "#s1");
        assertNotNull(defect, "会话层缺陷必须回流经验库，否则契约绿会话红的问题无法被记住");
        assertEquals("intent mismatch", defect.getSummary());
    }

    @Test
    void environmentProbeConclusionIsAttachedToReport() {
        ApiDefinition api = ApiDefinition.builder().method("GET").path("/home/content").build();
        when(targetHealthProbe.probe(anyString(), anyString()))
                .thenReturn(EnvironmentCheck.builder().module("mall-portal").target("http://x")
                        .reachable(false).detail("unreachable").build());
        when(customerScenarioRunner.supports("mall-portal")).thenReturn(false);
        when(openApiConfig.fetchApiDefinitions("mall-portal")).thenReturn(List.of(api));
        when(testCaseGenerator.generateTestCases(api)).thenReturn(List.of());
        when(reportGenerator.generateReport(eq("mall-portal"), anyList()))
                .thenReturn(TestReport.builder().id("r4").moduleName("mall-portal").build());

        TestReport report = agent.runTests("mall-portal");

        assertNotNull(report.getEnvironment(), "报告必须带上环境探针结论");
        assertFalse(report.getEnvironment().isReachable());
        assertEquals("unreachable", report.getEnvironment().getDetail());
    }
}
