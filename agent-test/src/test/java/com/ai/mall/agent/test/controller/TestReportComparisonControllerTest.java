package com.ai.mall.agent.test.controller;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.config.TestAccessInterceptor;
import com.ai.mall.agent.test.model.EnvironmentCheck;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import com.ai.mall.agent.test.service.agent.TestAgent;
import com.ai.mall.agent.test.service.report.TestReportComparisonService;
import com.ai.mall.agent.test.service.report.TestReportGenerator;
import com.ai.mall.agent.test.service.report.TestReportStore;
import com.ai.mall.agent.test.service.security.TestAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real comparison engine with isolated report storage and the real access interceptor. */
class TestReportComparisonControllerTest {
    private static final String TOKEN = "comparison-unit-test-token";
    private TestReportStore store;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        store = mock(TestReportStore.class);
        AgentTestConfig config = new AgentTestConfig();
        config.getAuth().setToken(TOKEN);
        config.getAuth().setJwtEnabled(false);
        TestAccessGuard guard = new TestAccessGuard(config);
        ReflectionTestUtils.invokeMethod(guard, "init");
        TestController controller = new TestController(mock(TestAgent.class), mock(TestReportGenerator.class),
                store, new TestReportComparisonService(store), config, guard);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TestApiExceptionHandler())
                .addInterceptors(new TestAccessInterceptor(guard, new ObjectMapper())).build();
    }

    @Test
    void anonymousRequestIsRejectedBeforeReadingReports() throws Exception {
        mvc.perform(get("/api/v1/test/reports/compare").param("baselineId", "before").param("currentId", "after"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test
    void evictedBaselineReturns404RatherThanGreenComparison() throws Exception {
        mvc.perform(get("/api/v1/test/reports/compare").header("X-Test-Agent-Token", TOKEN)
                        .param("baselineId", "evicted").param("currentId", "after"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void emptyReportIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/test/reports/compare").header("X-Test-Agent-Token", TOKEN)
                        .param("baselineId", " ").param("currentId", "after"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    @Test
    void differentModulesReturn422() throws Exception {
        when(store.findById("before")).thenReturn(report("before", "mall-portal", List.of()));
        when(store.findById("after")).thenReturn(report("after", "mall-admin", List.of()));
        mvc.perform(get("/api/v1/test/reports/compare").header("X-Test-Agent-Token", TOKEN)
                        .param("baselineId", "before").param("currentId", "after"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void exportsSyntheticAcceptanceExampleThroughProtectedApi() throws Exception {
        when(store.findById("before")).thenReturn(report("before", "mall-portal", List.of(
                result("regression", true), result("repair", false), result("persistent", false), result("removed", true))));
        when(store.findById("after")).thenReturn(report("after", "mall-portal", List.of(
                result("regression", false), result("repair", true), result("persistent", false), result("added", false))));
        String body = mvc.perform(get("/api/v1/test/reports/compare").header("X-Test-Agent-Token", TOKEN)
                        .param("baselineId", "before").param("currentId", "after"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comparable").value(true))
                .andExpect(jsonPath("$.counts.NEW_FAILURE").value(1))
                .andExpect(jsonPath("$.counts.FIXED").value(1))
                .andExpect(jsonPath("$.counts.PERSISTING_FAILURE").value(1))
                .andExpect(jsonPath("$.counts.NEW_CASE").value(1))
                .andExpect(jsonPath("$.counts.REMOVED_CASE").value(1))
                .andReturn().getResponse().getContentAsString();
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/report-comparison-example.json"), body);
    }

    private TestResult result(String name, boolean passed) {
        return TestResult.builder().testCaseId(java.util.UUID.randomUUID().toString())
                .testCaseName(name).method("GET").apiPath("/fixture/" + name)
                .passed(passed).actualStatusCode(passed ? 200 : 500)
                .errorMessage(passed ? null : "Synthetic assertion failure").build();
    }

    private TestReport report(String id, String module, List<TestResult> results) {
        return TestReport.builder().id(id).moduleName(module).totalTests(results.size()).results(results)
                .environment(EnvironmentCheck.builder().reachable(true).skipped(false).target("isolated-test-fixture").build()).build();
    }
}
