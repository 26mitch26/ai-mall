package com.ai.mall.agent.test.config;

import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.Parameter;
import com.ai.mall.agent.test.model.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiConfig {

    private final AgentTestConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Fetch API definitions from the target service's OpenAPI specification endpoint.
     *
     * @return list of API definitions parsed from the OpenAPI spec
     */
    public List<ApiDefinition> fetchApiDefinitions(String moduleName) {
        String moduleBaseUrl = config.getModuleBaseUrls() == null
                ? null
                : config.getModuleBaseUrls().get(moduleName);
        String targetBaseUrl = moduleBaseUrl == null ? config.getBaseUrl() : moduleBaseUrl;
        String openApiUrl = targetBaseUrl + "/v3/api-docs";
        log.info("Fetching OpenAPI specification from: {}", openApiUrl);

        try {
            String specJson = restTemplate.getForObject(openApiUrl, String.class);
            if (specJson == null) {
                log.warn("Empty response from OpenAPI endpoint: {}", openApiUrl);
                return List.of();
            }
            return parseOpenApiSpec(specJson);
        } catch (Exception e) {
            log.warn("Failed to fetch OpenAPI spec from {}: {}. Using fallback definitions.", openApiUrl, e.getMessage());
            return getFallbackApiDefinitions();
        }
    }

    /**
     * Parse an OpenAPI 3.x JSON specification into ApiDefinition objects.
     */
    private List<ApiDefinition> parseOpenApiSpec(String specJson) {
        List<ApiDefinition> apis = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(specJson);
            JsonNode paths = root.get("paths");
            if (paths == null || paths.isEmpty()) {
                log.warn("No paths found in OpenAPI specification");
                return apis;
            }

            Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
            while (pathIterator.hasNext()) {
                Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
                String path = pathEntry.getKey();
                JsonNode methods = pathEntry.getValue();

                Iterator<Map.Entry<String, JsonNode>> methodIterator = methods.fields();
                while (methodIterator.hasNext()) {
                    Map.Entry<String, JsonNode> methodEntry = methodIterator.next();
                    String method = methodEntry.getKey().toUpperCase();

                    // Skip non-HTTP method keys
                    if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").contains(method)) {
                        continue;
                    }

                    JsonNode operation = methodEntry.getValue();
                    String summary = operation.has("summary") ? operation.get("summary").asText() : "";
                    String description = operation.has("description") ? operation.get("description").asText() : "";

                    List<Parameter> parameters = parseParameters(operation, path);
                    Map<String, Response> responses = parseResponses(operation);

                    ApiDefinition apiDef = ApiDefinition.builder()
                            .path(path)
                            .method(method)
                            .summary(summary.isEmpty() ? description : summary)
                            .parameters(parameters)
                            .responses(responses)
                            .build();

                    apis.add(apiDef);
                }
            }

            log.info("Parsed {} API definitions from OpenAPI specification", apis.size());
        } catch (Exception e) {
            log.error("Failed to parse OpenAPI specification: {}", e.getMessage(), e);
        }

        return apis;
    }

    private List<Parameter> parseParameters(JsonNode operation, String path) {
        List<Parameter> parameters = new ArrayList<>();

        // Parse path parameters from the path template
        List<String> pathParams = extractPathParameters(path);
        for (String paramName : pathParams) {
            parameters.add(Parameter.builder()
                    .name(paramName)
                    .in("path")
                    .type("string")
                    .required(true)
                    .description("Path parameter: " + paramName)
                    .build());
        }

        JsonNode paramsNode = operation.get("parameters");
        if (paramsNode != null && paramsNode.isArray()) {
            for (JsonNode paramNode : paramsNode) {
                String name = paramNode.has("name") ? paramNode.get("name").asText() : "";
                String in = paramNode.has("in") ? paramNode.get("in").asText() : "query";
                boolean required = paramNode.has("required") && paramNode.get("required").asBoolean();
                String description = paramNode.has("description") ? paramNode.get("description").asText() : "";

                String type = "string";
                if (paramNode.has("schema")) {
                    JsonNode schema = paramNode.get("schema");
                    type = schema.has("type") ? schema.get("type").asText() : "string";
                }

                // Avoid duplicating path parameters already extracted
                if ("path".equals(in) && parameters.stream().anyMatch(p -> p.getName().equals(name))) {
                    continue;
                }

                parameters.add(Parameter.builder()
                        .name(name)
                        .in(in)
                        .type(type)
                        .required(required)
                        .description(description)
                        .build());
            }
        }

        // Parse request body for POST/PUT/PATCH
        if (operation.has("requestBody")) {
            JsonNode requestBody = operation.get("requestBody");
            boolean required = requestBody.has("required") && requestBody.get("required").asBoolean();
            JsonNode content = requestBody.get("content");
            if (content != null) {
                Iterator<String> contentTypes = content.fieldNames();
                while (contentTypes.hasNext()) {
                    String contentType = contentTypes.next();
                    JsonNode mediaType = content.get(contentType);
                    if (mediaType.has("schema")) {
                        JsonNode schema = mediaType.get("schema");
                        parameters.add(Parameter.builder()
                                .name("requestBody")
                                .in("body")
                                .type(contentType)
                                .required(required)
                                .description("Request body (" + contentType + ")")
                                .build());

                        // If schema has properties, add them as body parameters
                        if (schema.has("properties")) {
                            JsonNode properties = schema.get("properties");
                            Iterator<Map.Entry<String, JsonNode>> propFields = properties.fields();
                            while (propFields.hasNext()) {
                                Map.Entry<String, JsonNode> prop = propFields.next();
                                String propType = prop.getValue().has("type") ? prop.getValue().get("type").asText() : "string";
                                parameters.add(Parameter.builder()
                                        .name(prop.getKey())
                                        .in("body")
                                        .type(propType)
                                        .required(false)
                                        .description("Body field: " + prop.getKey())
                                        .build());
                            }
                        }
                        break; // Only process the first content type
                    }
                }
            }
        }

        return parameters;
    }

    private List<String> extractPathParameters(String path) {
        List<String> params = new ArrayList<>();
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment.startsWith("{") && segment.endsWith("}")) {
                params.add(segment.substring(1, segment.length() - 1));
            }
        }
        return params;
    }

    private Map<String, Response> parseResponses(JsonNode operation) {
        Map<String, Response> responses = new HashMap<>();
        JsonNode responsesNode = operation.get("responses");
        if (responsesNode != null) {
            Iterator<Map.Entry<String, JsonNode>> respIterator = responsesNode.fields();
            while (respIterator.hasNext()) {
                Map.Entry<String, JsonNode> respEntry = respIterator.next();
                String statusCode = respEntry.getKey();
                JsonNode respValue = respEntry.getValue();
                String description = respValue.has("description") ? respValue.get("description").asText() : "";

                String schema = "";
                if (respValue.has("content")) {
                    JsonNode content = respValue.get("content");
                    Iterator<String> contentTypes = content.fieldNames();
                    if (contentTypes.hasNext()) {
                        String contentType = contentTypes.next();
                        JsonNode mediaType = content.get(contentType);
                        if (mediaType.has("schema")) {
                            schema = mediaType.get("schema").toString();
                        }
                    }
                }

                responses.put(statusCode, Response.builder()
                        .description(description)
                        .schema(schema)
                        .build());
            }
        }
        return responses;
    }

    /**
     * Fallback API definitions when the OpenAPI endpoint is unreachable.
     */
    public List<ApiDefinition> getFallbackApiDefinitions() {
        log.info("Using fallback API definitions");
        List<ApiDefinition> apis = new ArrayList<>();

        apis.add(ApiDefinition.builder()
                .path("/home/content")
                .method("GET")
                .summary("获取商城首页聚合数据")
                .parameters(List.of())
                .responses(okResponse())
                .build());

        apis.add(ApiDefinition.builder()
                .path("/product/search")
                .method("GET")
                .summary("搜索商品")
                .parameters(List.of(
                        Parameter.builder().name("keyword").in("query").type("string").required(false).build(),
                        Parameter.builder().name("pageNum").in("query").type("integer").required(false).build(),
                        Parameter.builder().name("pageSize").in("query").type("integer").required(false).build()
                ))
                .responses(okResponse())
                .build());

        apis.add(ApiDefinition.builder()
                .path("/product/categoryTreeList")
                .method("GET")
                .summary("获取商品分类树")
                .parameters(List.of())
                .responses(okResponse())
                .build());

        apis.add(ApiDefinition.builder()
                .path("/brand/recommendList")
                .method("GET")
                .summary("获取推荐品牌")
                .parameters(List.of(
                        Parameter.builder().name("pageNum").in("query").type("integer").required(false).build(),
                        Parameter.builder().name("pageSize").in("query").type("integer").required(false).build()
                ))
                .responses(okResponse())
                .build());

        return apis;
    }

    private Map<String, Response> okResponse() {
        return Map.of("200", Response.builder()
                .description("请求成功")
                .schema("{\"type\":\"object\"}")
                .build());
    }
}
