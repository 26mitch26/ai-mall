package com.ai.mall.agent.test.service.mcp;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.AssertionDetail;
import com.ai.mall.agent.test.model.McpTestRun;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import com.ai.mall.agent.test.service.report.TestReportStore;
import com.ai.mall.agent.test.service.security.TestAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpProtocolServiceTest {

    @Mock private McpRunRegistry runRegistry;
    @Mock private TestReportStore reportStore;

    private ObjectMapper mapper;
    private McpProtocolService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        AgentTestConfig config = new AgentTestConfig();
        config.setModules(List.of("mall-portal", "agent-customer-scenarios"));
        config.setMaxApisPerRun(8);
        config.getMcp().setMaxWaitSeconds(300);
        config.getMcp().setMaxReportResults(50);
        TestAccessGuard guard = new TestAccessGuard(config);
        service = new McpProtocolService(mapper, config, guard, runRegistry, reportStore,
                new TestReportPresenter(mapper));
    }

    @Test
    void initializeNegotiatesOnlySupportedProtocolVersion() throws Exception {
        var accepted = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"codebuddy","version":"1"}}}
                """), "caller");
        assertEquals("2025-06-18", accepted.path("result").path("protocolVersion").asText());
        assertEquals("ai-mall-test-agent", accepted.path("result").path("serverInfo").path("name").asText());

        var rejected = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
                """), "caller");
        assertEquals(-32602, rejected.path("error").path("code").asInt());
    }

    @Test
    void toolsListExposesClosedSchemas() throws Exception {
        var response = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """), "caller");
        JsonNode tools = response.path("result").path("tools");
        assertEquals(5, tools.size());
        for (JsonNode tool : tools) {
            assertTrue(tool.path("description").asText().length() > 20,
                    "tool " + tool.path("name").asText() + " needs a usable description");
            assertFalse(tool.path("inputSchema").path("properties").isMissingNode(),
                    "tool " + tool.path("name").asText() + " should declare a properties schema");
            assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean(true),
                    "tool " + tool.path("name").asText() + " must not accept extra arguments");
        }
    }

    @Test
    void handlesPingNotificationsAndProtocolErrors() throws Exception {
        assertTrue(service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":"p","method":"ping"}
                """), "caller").path("result").isObject());
        assertNull(service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """), "caller"));
        assertEquals(-32601, service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"resources/list"}
                """), "caller").path("error").path("code").asInt());
        assertEquals(-32600, service.handle(mapper.readTree("""
                {"jsonrpc":"1.0","id":1,"method":"ping"}
                """), "caller").path("error").path("code").asInt());
    }

    @Test
    void listModulesReportsWhitelistAndSafetyFlags() throws Exception {
        var response = call("list_test_modules", "{}");
        assertFalse(response.path("result").path("isError").asBoolean());
        JsonNode payload = text(response);
        assertEquals("mall-portal", payload.path("modules").get(0).path("name").asText());
        assertEquals("openapi-contract", payload.path("modules").get(0).path("kind").asText());
        assertEquals("conversation-scenarios", payload.path("modules").get(1).path("kind").asText());
        assertTrue(payload.path("safeDemoMode").asBoolean());
    }

    @Test
    void runTestsReturnsHandleInsteadOfBlocking() throws Exception {
        McpTestRun run = finishedRun();
        when(runRegistry.submit(eq("mall-portal"), anyString())).thenReturn(run);
        when(runRegistry.awaitCompletion(any(), anyInt())).thenReturn(true);

        var response = call("run_tests", "{\"module\":\"mall-portal\"}");
        JsonNode payload = text(response);
        assertEquals("run-1", payload.path("runId").asText());
        assertEquals("SUCCEEDED", payload.path("status").asText());
        assertEquals("report-1", payload.path("reportId").asText());
        assertTrue(payload.path("hint").isMissingNode(), "a completed run should not ask the caller to poll");
    }

    @Test
    void runTestsKeepsHintWhenStillRunning() throws Exception {
        McpTestRun run = new McpTestRun("run-2", "mall-portal", "caller", LocalDateTime.now());
        when(runRegistry.submit(eq("mall-portal"), anyString())).thenReturn(run);
        when(runRegistry.awaitCompletion(any(), anyInt())).thenReturn(false);

        JsonNode payload = text(call("run_tests", "{\"module\":\"mall-portal\"}"));
        assertEquals("RUNNING", payload.path("status").asText());
        assertTrue(payload.path("hint").asText().contains("get_run_status"));
    }

    @Test
    void runTestsRejectsModuleOutsideWhitelist() throws Exception {
        assertEquals(-32602, call("run_tests", "{\"module\":\"mall-search\"}").path("error").path("code").asInt());
    }

    @Test
    void runTestsSurfacesQueueRejectionAsToolError() throws Exception {
        when(runRegistry.submit(eq("mall-portal"), anyString()))
                .thenThrow(new McpRunRejectedException("queue full"));
        var response = call("run_tests", "{\"module\":\"mall-portal\"}");
        assertTrue(response.path("result").path("isError").asBoolean());
        assertTrue(response.path("result").path("content").get(0).path("text").asText().contains("queue full"));
    }

    @Test
    void rejectsUnknownToolAndUnexpectedArguments() throws Exception {
        assertEquals(-32602, call("drop_database", "{}").path("error").path("code").asInt());
        assertEquals(-32602, call("run_tests", "{\"module\":\"mall-portal\",\"force\":true}")
                .path("error").path("code").asInt());
        assertEquals(-32602, call("run_tests", "{\"module\":\"mall-portal\",\"waitSeconds\":9999}")
                .path("error").path("code").asInt());
    }

    @Test
    void getTestReportProjectsFailuresWithTheirFailedAssertions() throws Exception {
        when(reportStore.findById("report-1")).thenReturn(sampleReport());
        JsonNode payload = text(call("get_test_report", "{\"reportId\":\"report-1\"}"));
        assertEquals(2, payload.path("totalTests").intValue());
        assertEquals(1, payload.path("failedCount").intValue());
        JsonNode failed = payload.path("failedCases").get(0);
        assertEquals("GET /api/orders", failed.path("testCaseName").asText());
        assertEquals("Pagination Invariant Check (total >= list.size)",
                failed.path("failedAssertions").get(0).path("assertionName").asText());
        assertTrue(payload.path("results").isMissingNode(), "full case details should stay opt-in");
    }

    @Test
    void getTestReportRejectsUnknownId() throws Exception {
        when(reportStore.findById("missing")).thenReturn(null);
        assertEquals(-32602, call("get_test_report", "{\"reportId\":\"missing\"}").path("error").path("code").asInt());
    }

    private JsonNode call(String tool, String arguments) throws Exception {
        return service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(tool, arguments)), "caller");
    }

    private JsonNode text(JsonNode response) throws Exception {
        return mapper.readTree(response.path("result").path("content").get(0).path("text").asText());
    }

    private McpTestRun finishedRun() {
        McpTestRun run = new McpTestRun("run-1", "mall-portal", "caller", LocalDateTime.now());
        run.setReportId("report-1");
        run.setTotalTests(2);
        run.setFailedTests(1);
        run.setStatus(McpTestRun.Status.SUCCEEDED);
        run.setFinishedAt(LocalDateTime.now());
        return run;
    }

    private TestReport sampleReport() {
        AssertionDetail failedAssertion = AssertionDetail.builder()
                .assertionName("Pagination Invariant Check (total >= list.size)")
                .passed(false).expected("total >= 1").actual("total = 0").build();
        TestResult passed = TestResult.builder().testCaseId("c1").testCaseName("GET /api/brands")
                .passed(true).actualStatusCode(200).executionTime(12L)
                .assertionDetails(List.of(AssertionDetail.builder().assertionName("Status Code (contract)")
                        .passed(true).expected("200").actual("200").build()))
                .build();
        TestResult failed = TestResult.builder().testCaseId("c2").testCaseName("GET /api/orders")
                .passed(false).actualStatusCode(200).executionTime(30L)
                .assertionDetails(List.of(failedAssertion)).build();
        return TestReport.builder().id("report-1").moduleName("mall-portal")
                .totalTests(2).passedTests(1).failedTests(1).passRate(50.0)
                .assertionsTotal(2).assertionsPassed(1).assertionsFailed(1)
                .results(List.of(passed, failed)).startTime(LocalDateTime.now()).build();
    }
}
