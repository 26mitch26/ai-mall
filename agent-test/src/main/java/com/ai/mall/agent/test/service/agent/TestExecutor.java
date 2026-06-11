package com.ai.mall.agent.test.service.agent;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.AssertionDetail;
import com.ai.mall.agent.test.model.TestCase;
import com.ai.mall.agent.test.model.TestResult;
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

    public TestResult execute(TestCase testCase) {
        log.info("Executing test case: {} [{} {}]", testCase.getName(), testCase.getMethod(), testCase.getApiPath());

        long startTime = System.currentTimeMillis();
        List<AssertionDetail> assertions = new ArrayList<>();
        String testCaseId = testCase.getId() != null ? testCase.getId() : UUID.randomUUID().toString();

        try {
            if (config.isOfflineMode()) {
                return executeOffline(testCase, testCaseId, startTime);
            }

            // Build the full URL
            String url = buildUrl(testCase);
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

            // Assertion 1: Status code matches expected
            boolean statusMatch = actualStatusCode == testCase.getExpectedStatusCode();
            assertions.add(AssertionDetail.builder()
                    .assertionName("Status Code Check")
                    .passed(statusMatch)
                    .expected(String.valueOf(testCase.getExpectedStatusCode()))
                    .actual(String.valueOf(actualStatusCode))
                    .message(statusMatch ? "Status code matches expected" : "Expected " + testCase.getExpectedStatusCode() + " but got " + actualStatusCode)
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

            // Assertion 3: Response body is valid JSON when Content-Type is JSON
            String contentType = response.getHeaders().getContentType() != null
                    ? response.getHeaders().getContentType().toString() : "";
            if (contentType.contains("json") && responseBody != null && !responseBody.isBlank()) {
                boolean validJson = isValidJson(responseBody);
                assertions.add(AssertionDetail.builder()
                        .assertionName("JSON Validity Check")
                        .passed(validJson)
                        .expected("Valid JSON")
                        .actual(validJson ? "Valid JSON" : "Invalid JSON")
                        .message(validJson ? "Response body is valid JSON" : "Response body is not valid JSON")
                        .build());

                // Assertion 4: JSON schema validation (basic - check it has content)
                if (validJson) {
                    boolean hasContent = !responseBody.trim().equals("{}") && !responseBody.trim().equals("[]");
                    assertions.add(AssertionDetail.builder()
                            .assertionName("Response Content Check")
                            .passed(hasContent)
                            .expected("Non-empty response")
                            .actual(hasContent ? "Has content" : "Empty response")
                            .message(hasContent ? "Response body contains data" : "Response body is empty object/array")
                            .build());
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
     * Execute test case in offline simulation mode.
     */
    private TestResult executeOffline(TestCase testCase, String testCaseId, long startTime) {
        log.info("Offline mode: simulating test case: {}", testCase.getName());
        List<AssertionDetail> assertions = new ArrayList<>();

        // Simulate API call logic
        int actualStatusCode;
        boolean hasInvalidParam = testCase.getRequestParams() != null
                && testCase.getRequestParams().containsValue("invalid_value");
        boolean hasMissingRequired = testCase.getExpectedStatusCode() == 400
                && !hasInvalidParam;

        if (hasInvalidParam) {
            actualStatusCode = 400;
        } else if (hasMissingRequired) {
            actualStatusCode = 400;
        } else {
            actualStatusCode = 200;
        }

        long executionTime = System.currentTimeMillis() - startTime;

        boolean statusMatch = actualStatusCode == testCase.getExpectedStatusCode();
        assertions.add(AssertionDetail.builder()
                .assertionName("Status Code Check (simulated)")
                .passed(statusMatch)
                .expected(String.valueOf(testCase.getExpectedStatusCode()))
                .actual(String.valueOf(actualStatusCode))
                .message(statusMatch ? "Status code matches expected" : "Expected " + testCase.getExpectedStatusCode() + " but got " + actualStatusCode)
                .build());

        boolean passed = assertions.stream().allMatch(AssertionDetail::isPassed);

        return TestResult.builder()
                .testCaseId(testCaseId)
                .testCaseName(testCase.getName())
                .passed(passed)
                .actualStatusCode(actualStatusCode)
                .actualResponse(passed ? "[Simulated] Success" : "[Simulated] Error")
                .executionTime(executionTime)
                .timestamp(LocalDateTime.now())
                .assertionDetails(assertions)
                .build();
    }

    /**
     * Build the full URL for a test case, replacing path parameters and adding query parameters.
     */
    private String buildUrl(TestCase testCase) {
        String baseUrl = config.getBaseUrl();
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