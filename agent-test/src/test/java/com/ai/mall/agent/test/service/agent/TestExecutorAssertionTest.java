package com.ai.mall.agent.test.service.agent;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.TestCase;
import com.ai.mall.agent.test.model.TestResult;
import com.ai.mall.agent.test.service.assertion.OpenApiSchemaValidator;
import com.ai.mall.agent.test.service.assertion.SemanticAssertionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 执行器断言分档测试：验证精确断言（契约背书）与弱断言（4xx 拒绝）两档判定语义。
 *
 * <p>弱断言的存在理由：文档未声明具体错误码时，"返回哪个 4xx"属框架实现细节，
 * 不可作精确契约。但"非法输入必须被拒绝"（4xx）仍是可验证的底线——
 * 若接口对非法输入返回 2xx（吞掉错误），弱断言必须判 FAIL。
 */
class TestExecutorAssertionTest {

    private RestTemplate restTemplate;
    private TestExecutor executor;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        AgentTestConfig config = new AgentTestConfig();
        config.setBaseUrl("http://target");
        executor = new TestExecutor(config, restTemplate, new ObjectMapper(),
                new SemanticAssertionService(new ObjectMapper(), new OpenApiSchemaValidator()));
    }

    @Test
    void stableIdentitySurvivesSuccessfulAndFailedExecution() {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), isNull(), eq(String.class), anyMap()))
                .thenReturn(ResponseEntity.ok("{}"));
        TestResult success = executor.execute(testCase(true, 200));
        assertEquals("GET", success.getMethod());
        assertEquals("/api/products/1", success.getApiPath());
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), isNull(), eq(String.class), anyMap()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));
        TestResult failure = executor.execute(testCase(true, 200));
        assertEquals("GET", failure.getMethod());
        assertEquals("/api/products/1", failure.getApiPath());
        assertFalse(failure.isPassed());
    }

    private TestCase testCase(boolean strict, int expectedStatus) {
        return TestCase.builder()
                .id("t-1")
                .name("case")
                .apiPath("/api/products/1")
                .method("GET")
                .requestParams(Map.of())
                .expectedStatusCode(expectedStatus)
                .strictExpectation(strict)
                .build();
    }

    private void stubResponse(int statusCode) {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), isNull(),
                any(Class.class), anyMap()))
                .thenReturn(ResponseEntity.status(statusCode).body("{\"code\":200,\"data\":null}"));
    }

    @Test
    @DisplayName("精确断言：实际状态码与契约期望相等 → PASS")
    void strictShouldPassOnExactMatch() {
        stubResponse(200);

        TestResult result = executor.execute(testCase(true, 200));

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("精确断言：实际状态码偏离契约期望 → FAIL")
    void strictShouldFailOnMismatch() {
        stubResponse(400);

        TestResult result = executor.execute(testCase(true, 200));

        assertFalse(result.isPassed(), "契约声明 200 却返回 400，精确断言必须失败");
    }

    @Test
    @DisplayName("弱断言：任何 4xx 都算'正确拒绝'（期望 400 遇到实际 422 也 PASS）")
    void weakShouldPassOnAny4xxRejection() {
        stubResponse(422);

        TestResult result = executor.execute(testCase(false, 400));

        assertTrue(result.isPassed(),
                "文档未声明具体错误码，422 与 400 同为'服务端正确拒绝'，不应误报");
    }

    @Test
    @DisplayName("弱断言：非法输入被接口吞掉返回 2xx → FAIL（底线断言）")
    void weakShouldFailWhenInvalidInputAccepted() {
        stubResponse(200);

        TestResult result = executor.execute(testCase(false, 400));

        assertFalse(result.isPassed(),
                "非法输入返回 2xx 说明服务端未校验，弱断言必须拦截");
    }

    @Test
    @DisplayName("断言明细应标注档位：契约背书用 '(contract)'，降级用 '(4xx)'")
    void assertionDetailShouldRevealAssertionMode() {
        stubResponse(422);

        TestResult strictResult = executor.execute(testCase(true, 400));
        assertEquals("Status Code Check (contract)",
                strictResult.getAssertionDetails().get(0).getAssertionName());

        TestResult weakResult = executor.execute(testCase(false, 400));
        assertEquals("Client Rejection Check (4xx)",
                weakResult.getAssertionDetails().get(0).getAssertionName());
        assertEquals("4xx (client rejection)",
                weakResult.getAssertionDetails().get(0).getExpected());
    }

    // ======================== 语义断言集成（P1） ========================

    private void stubResponse(int statusCode, String body) {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), isNull(),
                any(Class.class), anyMap()))
                .thenReturn(ResponseEntity.status(statusCode).body(body));
    }

    private TestCase testCaseWithSchema(boolean strict, int expectedStatus, String schema) {
        TestCase tc = testCase(strict, expectedStatus);
        tc.setResponseSchema(schema);
        return tc;
    }

    @Test
    @DisplayName("吞错检测：HTTP 200 但业务码 500 → 状态码断言通过但整体 FAIL")
    void swallowedBusinessErrorShouldFailPipeline() {
        stubResponse(200, "{\"code\":500,\"message\":\"internal error\",\"data\":null}");

        TestResult result = executor.execute(testCase(true, 200));

        assertFalse(result.isPassed(), "HTTP 语义正确但业务码异常，语义断言必须兜住");
        assertTrue(result.getAssertionDetails().stream()
                .anyMatch(d -> d.getAssertionName().contains("Business Code Check")
                        && !d.isPassed()));
    }

    @Test
    @DisplayName("Schema 断言：契约声明 string 字段返回数字 → FAIL")
    void schemaViolationShouldFailPipeline() {
        stubResponse(200, "{\"code\":200,\"data\":{\"name\":123}}");
        String schema = """
                {"type":"object","properties":{"code":{"type":"integer"},
                 "data":{"type":"object","properties":{"name":{"type":"string"}}}}}""";

        TestResult result = executor.execute(testCaseWithSchema(true, 200, schema));

        assertFalse(result.isPassed());
        assertTrue(result.getAssertionDetails().stream()
                .anyMatch(d -> d.getAssertionName().equals("OpenAPI Schema Check")
                        && !d.isPassed()));
    }

    @Test
    @DisplayName("无 Schema 声明时不应产出 Schema 断言，不影响通过")
    void absentSchemaShouldNotProduceSchemaAssertion() {
        stubResponse(200, "{\"code\":200,\"data\":null}");

        TestResult result = executor.execute(testCase(true, 200));

        assertTrue(result.isPassed());
        assertTrue(result.getAssertionDetails().stream()
                .noneMatch(d -> d.getAssertionName().equals("OpenAPI Schema Check")));
    }
}
