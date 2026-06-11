package com.ai.mall.agent.test.service.generator;

import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.Parameter;
import com.ai.mall.agent.test.model.TestCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class TestCaseGenerator {

    public List<TestCase> generateTestCases(ApiDefinition api) {
        log.info("Generating test cases for API: {} {}", api.getMethod(), api.getPath());

        List<TestCase> testCases = new ArrayList<>();

        testCases.add(generateSuccessCase(api));
        testCases.add(generateMissingRequiredParamCase(api));
        testCases.add(generateInvalidParamCase(api));
        testCases.add(generateEdgeCaseForNumericParams(api));

        log.info("Generated {} test cases for {} {}", testCases.size(), api.getMethod(), api.getPath());
        return testCases;
    }

    private TestCase generateSuccessCase(ApiDefinition api) {
        Map<String, Object> params = generateValidParams(api);

        return TestCase.builder()
                .id(UUID.randomUUID().toString())
                .name("Success case for " + api.getMethod() + " " + api.getPath())
                .apiPath(api.getPath())
                .method(api.getMethod())
                .requestParams(params)
                .expectedStatusCode(200)
                .expectedResponse("Success response")
                .description("Test successful API call with valid parameters")
                .build();
    }

    private TestCase generateMissingRequiredParamCase(ApiDefinition api) {
        Map<String, Object> params = generateValidParams(api);

        if (api.getParameters() != null) {
            for (Parameter param : api.getParameters()) {
                if (param.isRequired() && !"path".equals(param.getIn())) {
                    params.remove(param.getName());
                    break;
                }
            }
        }

        return TestCase.builder()
                .id(UUID.randomUUID().toString())
                .name("Missing required parameter case for " + api.getMethod() + " " + api.getPath())
                .apiPath(api.getPath())
                .method(api.getMethod())
                .requestParams(params)
                .expectedStatusCode(400)
                .expectedResponse("Missing required parameter")
                .description("Test API call with missing required parameter")
                .build();
    }

    private TestCase generateInvalidParamCase(ApiDefinition api) {
        Map<String, Object> params = generateValidParams(api);

        if (api.getParameters() != null && !api.getParameters().isEmpty()) {
            Parameter firstParam = api.getParameters().get(0);
            params.put(firstParam.getName(), "invalid_value");
        }

        return TestCase.builder()
                .id(UUID.randomUUID().toString())
                .name("Invalid parameter case for " + api.getMethod() + " " + api.getPath())
                .apiPath(api.getPath())
                .method(api.getMethod())
                .requestParams(params)
                .expectedStatusCode(400)
                .expectedResponse("Invalid parameter")
                .description("Test API call with invalid parameter value")
                .build();
    }

    private TestCase generateEdgeCaseForNumericParams(ApiDefinition api) {
        Map<String, Object> params = generateValidParams(api);
        boolean hasNumericParam = false;

        if (api.getParameters() != null) {
            for (Parameter param : api.getParameters()) {
                String type = param.getType() != null ? param.getType().toLowerCase() : "";
                if (List.of("integer", "int", "number", "long").contains(type)) {
                    params.put(param.getName(), -1);  // negative value edge case
                    hasNumericParam = true;
                    break;
                }
            }
        }

        if (!hasNumericParam) {
            return null; // Skip this case if no numeric params
        }

        return TestCase.builder()
                .id(UUID.randomUUID().toString())
                .name("Edge case (negative value) for " + api.getMethod() + " " + api.getPath())
                .apiPath(api.getPath())
                .method(api.getMethod())
                .requestParams(params)
                .expectedStatusCode(400)
                .expectedResponse("Invalid parameter value")
                .description("Test API call with negative numeric value (edge case)")
                .build();
    }

    private Map<String, Object> generateValidParams(ApiDefinition api) {
        Map<String, Object> params = new HashMap<>();
        if (api.getParameters() != null) {
            for (Parameter param : api.getParameters()) {
                if ("body".equals(param.getIn())) {
                    // Skip body-type params in query params
                    continue;
                }
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
            case "array" -> List.of();
            case "object" -> Map.of();
            default -> "test_value";
        };
    }
}