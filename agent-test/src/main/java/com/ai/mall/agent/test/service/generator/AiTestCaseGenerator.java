package com.ai.mall.agent.test.service.generator;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.Parameter;
import com.ai.mall.agent.test.model.TestCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "test.agent.ai.enabled", havingValue = "true", matchIfMissing = false)
public class AiTestCaseGenerator {

    private final ChatClient.Builder chatClientBuilder;
    private final AgentTestConfig config;

    /**
     * Generate test cases using Spring AI OpenAI for intelligent test generation.
     * Falls back to basic generation if AI call fails.
     */
    public List<TestCase> generateTestCases(ApiDefinition api) {
        log.info("AI generating test cases for API: {} {}", api.getMethod(), api.getPath());

        try {
            ChatClient chatClient = chatClientBuilder.build();

            String prompt = buildPrompt(api);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response != null && !response.isBlank()) {
                List<TestCase> aiTestCases = parseAiResponse(response, api);
                if (!aiTestCases.isEmpty()) {
                    log.info("AI generated {} test cases for {} {}", aiTestCases.size(), api.getMethod(), api.getPath());
                    return aiTestCases;
                }
            }

            log.warn("AI returned empty response, falling back to basic generation");
            return generateBasicTestCases(api);

        } catch (Exception e) {
            log.warn("AI test generation failed: {}. Falling back to basic generation.", e.getMessage());
            return generateBasicTestCases(api);
        }
    }

    /**
     * Build a prompt for the AI to generate meaningful test cases.
     */
    private String buildPrompt(ApiDefinition api) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate 3-5 test cases for the following API. ");
        sb.append("Return each test case as a line with format: NAME|METHOD|PATH|PARAMS|EXPECTED_STATUS|DESCRIPTION\n");
        sb.append("Where PARAMS is a comma-separated list of key=value pairs, put NONE if no params.\n\n");
        sb.append("API Details:\n");
        sb.append("- Method: ").append(api.getMethod()).append("\n");
        sb.append("- Path: ").append(api.getPath()).append("\n");
        sb.append("- Summary: ").append(api.getSummary()).append("\n");

        if (api.getParameters() != null && !api.getParameters().isEmpty()) {
            sb.append("- Parameters:\n");
            for (Parameter param : api.getParameters()) {
                sb.append("  * ").append(param.getName())
                        .append(" (").append(param.getIn()).append(", ")
                        .append(param.getType()).append(", ")
                        .append(param.isRequired() ? "required" : "optional")
                        .append(")");
                if (param.getDescription() != null && !param.getDescription().isEmpty()) {
                    sb.append(": ").append(param.getDescription());
                }
                sb.append("\n");
            }
        }

        if (api.getResponses() != null && !api.getResponses().isEmpty()) {
            sb.append("- Expected Responses:\n");
            api.getResponses().forEach((code, resp) ->
                    sb.append("  * ").append(code).append(": ").append(resp.getDescription()).append("\n"));
        }

        sb.append("\nGenerate test cases that cover:");
        sb.append("\n1. Happy path (successful call with valid params)");
        sb.append("\n2. Missing required parameters (if applicable)");
        sb.append("\n3. Invalid parameter values");
        sb.append("\n4. Edge cases (empty values, boundary values)");
        sb.append("\n5. Authentication/authorization scenarios (if applicable)");

        return sb.toString();
    }

    /**
     * Parse AI response into TestCase objects.
     */
    private List<TestCase> parseAiResponse(String response, ApiDefinition api) {
        List<TestCase> testCases = new ArrayList<>();

        try {
            String[] lines = response.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }

                String[] parts = line.split("\\|");
                if (parts.length < 5) continue;

                String name = parts[0].trim();
                String method = parts[1].trim();
                String path = parts[2].trim();
                String paramsStr = parts[3].trim();
                int expectedStatus;
                try {
                    expectedStatus = Integer.parseInt(parts[4].trim());
                } catch (NumberFormatException e) {
                    expectedStatus = 200;
                }
                String description = parts.length > 5 ? parts[5].trim() : "";

                Map<String, Object> params = new HashMap<>();
                if (!"NONE".equalsIgnoreCase(paramsStr)) {
                    String[] paramPairs = paramsStr.split(",");
                    for (String pair : paramPairs) {
                        String[] kv = pair.split("=", 2);
                        if (kv.length == 2) {
                            params.put(kv[0].trim(), kv[1].trim());
                        }
                    }
                }

                testCases.add(TestCase.builder()
                        .id(UUID.randomUUID().toString())
                        .name(name)
                        .apiPath(path)
                        .method(method)
                        .requestParams(params)
                        .expectedStatusCode(expectedStatus)
                        .expectedResponse("AI generated assertion")
                        .description(description)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI response: {}", e.getMessage());
        }

        return testCases;
    }

    /**
     * Basic fallback test case generation (same as TestCaseGenerator logic).
     */
    private List<TestCase> generateBasicTestCases(ApiDefinition api) {
        List<TestCase> testCases = new ArrayList<>();

        // Success case
        Map<String, Object> validParams = generateValidParams(api);
        testCases.add(TestCase.builder()
                .id(UUID.randomUUID().toString())
                .name("AI-fallback: Success case for " + api.getMethod() + " " + api.getPath())
                .apiPath(api.getPath())
                .method(api.getMethod())
                .requestParams(validParams)
                .expectedStatusCode(200)
                .expectedResponse("Success response")
                .description("Test successful API call with valid parameters (AI fallback)")
                .build());

        // Invalid param case
        if (api.getParameters() != null && !api.getParameters().isEmpty()) {
            Parameter firstParam = api.getParameters().get(0);
            Map<String, Object> invalidParams = new HashMap<>(validParams);
            invalidParams.put(firstParam.getName(), "invalid_value");
            testCases.add(TestCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name("AI-fallback: Invalid param for " + api.getMethod() + " " + api.getPath())
                    .apiPath(api.getPath())
                    .method(api.getMethod())
                    .requestParams(invalidParams)
                    .expectedStatusCode(400)
                    .expectedResponse("Invalid parameter")
                    .description("Test API call with invalid parameter value (AI fallback)")
                    .build());
        }

        return testCases;
    }

    private Map<String, Object> generateValidParams(ApiDefinition api) {
        Map<String, Object> params = new HashMap<>();
        if (api.getParameters() != null) {
            for (Parameter param : api.getParameters()) {
                if ("body".equals(param.getIn())) continue;
                params.put(param.getName(), generateDefaultValue(param.getType()));
            }
        }
        return params;
    }

    private Object generateDefaultValue(String type) {
        if (type == null) return "test_value";
        return switch (type.toLowerCase()) {
            case "string" -> "test_value";
            case "integer", "int" -> 1;
            case "number", "double" -> 1.0;
            case "boolean" -> true;
            case "long" -> 1L;
            case "float" -> 1.0f;
            default -> "test_value";
        };
    }
}