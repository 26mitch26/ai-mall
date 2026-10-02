package com.ai.mall.agent.test.service.agent;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.AssertionDetail;
import com.ai.mall.agent.test.model.TestCase;
import com.ai.mall.agent.test.model.TestResult;
import com.ai.mall.agent.test.service.assertion.SemanticAssertionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestExecutor {

    private final AgentTestConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SemanticAssertionService semanticAssertionService;

    public TestResult execute(TestCase testCase) {
        return execute(testCase, config.getBaseUrl());
    }

    public TestResult execute(TestCase testCase, String targetBaseUrl) {
        log.info("Executing test case: {} [{} {}]", testCase.getName(), testCase.getMethod(), testCase.getApiPath());

        long startTime = System.currentTimeMillis();
        List<AssertionDetail> assertions = new ArrayList<>();
        String testCaseId = testCase.getId() != null ? testCase.getId() : UUID.randomUUID().toString();

        try {
            // Build the full URL
            String url = buildUrl(testCase, targetBaseUrl);
            HttpMethod httpMethod = HttpMethod.valueOf(testCase.getMethod().toUpperCase());

            log.debug("Sending {} request to: {}", httpMethod, url);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    httpMethod,
                    null,
                    String.class,
                    testCase.getRequestParams() != null ? testCase.getRequestParams() : Map.of()
            );

            long executionTime = System.currentTimeMillis() - startTime;
            int actualStatusCode = response.getStatusCode().value();
            String responseBody = response.getBody();

            // Assertion 1: 状态码断言分档
            // strict=true：期望码有 OpenAPI 契约背书，必须精确相等；
            // strict=false：文档未声明具体错误码，仅要求服务端以 4xx 正确拒绝非法输入
            //（"返回哪个 4xx"属框架实现细节，不作精确断言，避免把实现当契约造成误报）。
            boolean strict = testCase.isStrictExpectation();
            boolean statusMatch = strict
                    ? actualStatusCode == testCase.getExpectedStatusCode()
                    : (actualStatusCode >= 400 && actualStatusCode < 500);
            String expectedStatusDesc = strict
                    ? String.valueOf(testCase.getExpectedStatusCode())
                    : "4xx (client rejection)";
            assertions.add(AssertionDetail.builder()
                    .assertionName(strict ? "Status Code Check (contract)" : "Client Rejection Check (4xx)")
                    .passed(statusMatch)
                    .expected(expectedStatusDesc)
                    .actual(String.valueOf(actualStatusCode))
                    .message(statusMatch
                            ? (strict ? "Status code matches contract-declared expectation"
                                      : "Server correctly rejected invalid input with 4xx")
                            : (strict ? "Expected " + testCase.getExpectedStatusCode()
                                      + " but got " + actualStatusCode
                                      : "Expected 4xx rejection but got " + actualStatusCode))
                    .build());

            // Assertion 2: Response time within threshold
            boolean responseTimeOk = executionTime <= config.getResponseTimeThresholdMs();
            assertions.add(AssertionDetail.builder()
                    .assertionName("Response Time Check")
                    .passed(responseTimeOk)
                    .expected("< " + config.getResponseTimeThresholdMs() + "ms")
                    .actual(executionTime + "ms")
                    .message(responseTimeOk ? "Response time within threshold" : "Response time " + executionTime + "ms exceeds threshold " + config.getResponseTimeThresholdMs() + "ms")
                    .build());

            // Assertion 3: Response body is valid JSON
            boolean bodyIsJson = responseBody != null && !responseBody.isBlank()
                    && isValidJson(responseBody);
            String contentType = response.getHeaders().getContentType() != null
                    ? response.getHeaders().getContentType().toString() : "";
            if (bodyIsJson || contentType.contains("json")) {
                // 响应声明 JSON 但内容解析失败：给出 JSON 合法性断言；未声明也兜底校验
                assertions.add(AssertionDetail.builder()
                        .assertionName("JSON Validity Check")
                        .passed(bodyIsJson)
                        .expected("Valid JSON")
                        .actual(bodyIsJson ? "Valid JSON" : "Invalid JSON or empty body")
                        .message(bodyIsJson ? "Response body is valid JSON"
                                : "Response body is not valid JSON")
                        .build());

                // Assertion 4: JSON content check (basic - not empty object/array)
                if (bodyIsJson) {
                    boolean hasContent = !responseBody.trim().equals("{}")
                            && !responseBody.trim().equals("[]");
                    assertions.add(AssertionDetail.builder()
                            .assertionName("Response Content Check")
                            .passed(hasContent)
                            .expected("Non-empty response")
                            .actual(hasContent ? "Has content" : "Empty response")
                            .message(hasContent ? "Response body contains data"
                                    : "Response body is empty object/array")
                            .build());
                }

                // Assertion 5+: 语义断言（条件性启用）——业务码吞错检测、分页不变式、
                // OpenAPI Schema 结构校验。只依赖 JSON 合法性，不依赖 Content-Type 头
                //（部分网关/代理会丢失该头，语义一致性不受 HTTP 头影响）。
                if (bodyIsJson) {
                    assertions.addAll(semanticAssertionService.evaluate(
                            responseBody, actualStatusCode, testCase.getResponseSchema()));
                }
            }

            // Determine overall pass/fail based on all assertions
            boolean passed = assertions.stream().allMatch(AssertionDetail::isPassed);

            TestResult result = TestResult.builder()
                    .testCaseId(testCaseId)
                    .testCaseName(testCase.getName())
                    .passed(passed)
                    .actualStatusCode(actualStatusCode)
                    .actualResponse(responseBody != null ? truncate(responseBody, 500) : "")
                    .executionTime(executionTime)
                    .timestamp(LocalDateTime.now())
                    .assertionDetails(assertions)
                    .build();

            log.info("Test case {} [{}] -> {} ({}ms, {} assertions)",
                    testCase.getName(), testCase.getMethod(), passed ? "PASSED" : "FAILED",
                    executionTime, assertions.size());
            return result;

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;

            assertions.add(AssertionDetail.builder()
                    .assertionName("Connection Check")
                    .passed(false)
                    .expected("Successful connection")
                    .actual("Connection failed: " + e.getClass().getSimpleName())
                    .message(e.getMessage())
                    .build());

            log.error("Test case {} failed with error: {}", testCase.getName(), e.getMessage());

            return TestResult.builder()
                    .testCaseId(testCaseId)
                    .testCaseName(testCase.getName())
                    .passed(false)
                    .actualStatusCode(0)
                    .actualResponse("")
                    .errorMessage(e.getMessage())
                    .executionTime(executionTime)
                    .timestamp(LocalDateTime.now())
                    .assertionDetails(assertions)
                    .build();
        }
    }

    /**
     * Build the full URL for a test case, replacing path parameters and adding query parameters.
     */
    private String buildUrl(TestCase testCase, String baseUrl) {
        String path = testCase.getApiPath();

        // Replace path parameters like {id} with actual values from request params
        if (testCase.getRequestParams() != null) {
            for (Map.Entry<String, Object> entry : testCase.getRequestParams().entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                if (path.contains(placeholder)) {
                    path = path.replace(placeholder, String.valueOf(entry.getValue()));
                }
            }
        }

        // Remove remaining path parameter placeholders (use "test" as default)
        path = path.replaceAll("\\{[^}]+\\}", "test");

        String url = baseUrl + path;

        // Append query parameters (only those not used as path params)
        if (testCase.getRequestParams() != null && !testCase.getRequestParams().isEmpty()) {
            StringBuilder queryString = new StringBuilder();
            for (Map.Entry<String, Object> entry : testCase.getRequestParams().entrySet()) {
                String key = entry.getKey();
                // Skip path parameters already substituted
                if (testCase.getApiPath().contains("{" + key + "}")) {
                    continue;
                }
                // Skip body parameters
                if ("requestBody".equals(key) || key.startsWith("body.")) {
                    continue;
                }
                if (!queryString.isEmpty()) {
                    queryString.append("&");
                }
                queryString.append(key).append("=").append(entry.getValue());
            }
            if (!queryString.isEmpty()) {
                url = url + "?" + queryString;
            }
        }

        return url;
    }

    private boolean isValidJson(String json) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
