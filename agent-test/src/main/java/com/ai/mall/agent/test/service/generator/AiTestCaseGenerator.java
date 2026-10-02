package com.ai.mall.agent.test.service.generator;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.Parameter;
import com.ai.mall.agent.test.model.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "test.agent.ai.enabled", havingValue = "true", matchIfMissing = false)
public class AiTestCaseGenerator {

    private final ChatClient.Builder chatClientBuilder;
    private final AgentTestConfig config;
    private final ObjectMapper objectMapper;
    private final TestCaseContractGuard contractGuard;

    // ======================== API文档解析 ========================

    /**
     * 解析OpenAPI/Swagger文档，提取接口信息
     * 支持OpenAPI 3.x JSON格式和Swagger 2.x格式
     */
    public List<ApiDefinition> parseApiDocument(String openApiSpecJson) {
        log.info("开始解析API文档，提取接口信息");
        List<ApiDefinition> apiDefinitions = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(openApiSpecJson);
            JsonNode paths = root.get("paths");
            if (paths == null || paths.isEmpty()) {
                log.warn("API文档中未找到paths节点");
                return apiDefinitions;
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

                    if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").contains(method)) {
                        continue;
                    }

                    JsonNode operation = methodEntry.getValue();
                    String summary = operation.has("summary") ? operation.get("summary").asText() : "";
                    String description = operation.has("description") ? operation.get("description").asText() : "";

                    List<Parameter> parameters = extractParametersFromSpec(operation, path);
                    Map<String, com.ai.mall.agent.test.model.Response> responses = extractResponsesFromSpec(operation);

                    apiDefinitions.add(ApiDefinition.builder()
                            .path(path)
                            .method(method)
                            .summary(summary.isEmpty() ? description : summary)
                            .parameters(parameters)
                            .responses(responses)
                            .build());
                }
            }

            log.info("API文档解析完成，共提取{}个接口定义", apiDefinitions.size());
        } catch (Exception e) {
            log.error("解析API文档失败: {}", e.getMessage(), e);
        }

        return apiDefinitions;
    }

    private List<Parameter> extractParametersFromSpec(JsonNode operation, String path) {
        List<Parameter> parameters = new ArrayList<>();

        // 从路径模板中提取路径参数
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment.startsWith("{") && segment.endsWith("}")) {
                String paramName = segment.substring(1, segment.length() - 1);
                parameters.add(Parameter.builder()
                        .name(paramName).in("path").type("string").required(true)
                        .description("路径参数: " + paramName).build());
            }
        }

        JsonNode paramsNode = operation.get("parameters");
        if (paramsNode != null && paramsNode.isArray()) {
            for (JsonNode paramNode : paramsNode) {
                String name = paramNode.has("name") ? paramNode.get("name").asText() : "";
                String in = paramNode.has("in") ? paramNode.get("in").asText() : "query";
                boolean required = paramNode.has("required") && paramNode.get("required").asBoolean();
                String desc = paramNode.has("description") ? paramNode.get("description").asText() : "";
                String type = "string";
                if (paramNode.has("schema")) {
                    JsonNode schema = paramNode.get("schema");
                    type = schema.has("type") ? schema.get("type").asText() : "string";
                }
                if ("path".equals(in) && parameters.stream().anyMatch(p -> p.getName().equals(name))) {
                    continue;
                }
                parameters.add(Parameter.builder()
                        .name(name).in(in).type(type).required(required).description(desc).build());
            }
        }

        // 解析requestBody
        if (operation.has("requestBody")) {
            JsonNode requestBody = operation.get("requestBody");
            boolean required = requestBody.has("required") && requestBody.get("required").asBoolean();
            JsonNode content = requestBody.get("content");
            if (content != null) {
                Iterator<String> contentTypes = content.fieldNames();
                if (contentTypes.hasNext()) {
                    String contentType = contentTypes.next();
                    parameters.add(Parameter.builder()
                            .name("requestBody").in("body").type(contentType).required(required)
                            .description("请求体 (" + contentType + ")").build());
                }
            }
        }

        return parameters;
    }

    private Map<String, com.ai.mall.agent.test.model.Response> extractResponsesFromSpec(JsonNode operation) {
        Map<String, com.ai.mall.agent.test.model.Response> responses = new HashMap<>();
        JsonNode responsesNode = operation.get("responses");
        if (responsesNode != null) {
            Iterator<Map.Entry<String, JsonNode>> respIterator = responsesNode.fields();
            while (respIterator.hasNext()) {
                Map.Entry<String, JsonNode> respEntry = respIterator.next();
                String statusCode = respEntry.getKey();
                JsonNode respValue = respEntry.getValue();
                String desc = respValue.has("description") ? respValue.get("description").asText() : "";
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
                responses.put(statusCode, com.ai.mall.agent.test.model.Response.builder()
                        .description(desc).schema(schema).build());
            }
        }
        return responses;
    }

    // ======================== 正常场景测试用例生成 ========================

    /**
     * 生成正常场景测试用例
     * 覆盖：合法参数、必填参数组合、可选参数组合
     */
    public List<TestCase> generateNormalCases(ApiDefinition api) {
        log.info("生成正常场景测试用例: {} {}", api.getMethod(), api.getPath());
        List<TestCase> cases = new ArrayList<>();

        // 1. 仅必填参数的正常调用
        Map<String, Object> requiredParams = generateRequiredParams(api);
        cases.add(TestCase.builder()
                .id(UUID.randomUUID().toString())
                .name("正常场景-必填参数: " + api.getMethod() + " " + api.getPath())
                .apiPath(api.getPath())
                .method(api.getMethod())
                .requestParams(requiredParams)
                .expectedStatusCode(200)
                .expectedResponse("成功响应")
                .description("使用必填参数进行正常调用，期望返回200")
                .build());

        // 2. 全部参数（必填+可选）的正常调用
        Map<String, Object> allParams = generateValidParams(api);
        if (allParams.size() > requiredParams.size()) {
            cases.add(TestCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name("正常场景-全部参数: " + api.getMethod() + " " + api.getPath())
                    .apiPath(api.getPath())
                    .method(api.getMethod())
                    .requestParams(allParams)
                    .expectedStatusCode(200)
                    .expectedResponse("成功响应")
                    .description("使用全部参数（必填+可选）进行正常调用，期望返回200")
                    .build());
        }

        // 3. 针对每个可选参数单独缺失的场景（确保可选参数缺失不影响正常调用）
        if (api.getParameters() != null) {
            List<Parameter> optionalParams = api.getParameters().stream()
                    .filter(p -> !p.isRequired() && !"body".equals(p.getIn()))
                    .collect(Collectors.toList());
            for (Parameter optional : optionalParams) {
                Map<String, Object> paramsWithoutOptional = new HashMap<>(allParams);
                paramsWithoutOptional.remove(optional.getName());
                cases.add(TestCase.builder()
                        .id(UUID.randomUUID().toString())
                        .name("正常场景-缺少可选参数[" + optional.getName() + "]: " + api.getMethod() + " " + api.getPath())
                        .apiPath(api.getPath())
                        .method(api.getMethod())
                        .requestParams(paramsWithoutOptional)
                        .expectedStatusCode(200)
                        .expectedResponse("成功响应")
                        .description("缺少可选参数" + optional.getName() + "，期望仍返回200")
                        .build());
            }
        }

        // 期望值契约对齐：正向用例锚定文档声明成功码，未声明成功响应的正向用例作废
        List<TestCase> aligned = contractGuard.align(cases, api);
        log.info("正常场景测试用例生成完成，共{}条（契约对齐后）", aligned.size());
        return aligned;
    }

    // ======================== 异常场景测试用例生成 ========================

    /**
     * 生成异常场景测试用例（4xx/5xx）
     * 覆盖：缺少必填参数、无效参数值、未授权、资源不存在
     */
    public List<TestCase> generateErrorCases(ApiDefinition api) {
        log.info("生成异常场景测试用例: {} {}", api.getMethod(), api.getPath());
        List<TestCase> cases = new ArrayList<>();

        // 1. 缺少必填参数 → 400
        if (api.getParameters() != null) {
            List<Parameter> requiredParams = api.getParameters().stream()
                    .filter(Parameter::isRequired)
                    .collect(Collectors.toList());
            for (Parameter required : requiredParams) {
                Map<String, Object> paramsMissing = generateValidParams(api);
                paramsMissing.remove(required.getName());
                cases.add(TestCase.builder()
                        .id(UUID.randomUUID().toString())
                        .name("异常场景-缺少必填参数[" + required.getName() + "]: " + api.getMethod() + " " + api.getPath())
                        .apiPath(api.getPath())
                        .method(api.getMethod())
                        .requestParams(paramsMissing)
                        .expectedStatusCode(400)
                        .expectedResponse("参数校验失败")
                        .description("缺少必填参数" + required.getName() + "，期望返回400")
                        .build());
            }
        }

        // 2. 参数类型不匹配 → 400
        if (api.getParameters() != null) {
            for (Parameter param : api.getParameters()) {
                if ("body".equals(param.getIn())) continue;
                String mismatchValue = getTypeMismatchValue(param.getType());
                if (mismatchValue != null) {
                    Map<String, Object> invalidTypeParams = generateValidParams(api);
                    invalidTypeParams.put(param.getName(), mismatchValue);
                    cases.add(TestCase.builder()
                            .id(UUID.randomUUID().toString())
                            .name("异常场景-参数类型不匹配[" + param.getName() + "]: " + api.getMethod() + " " + api.getPath())
                            .apiPath(api.getPath())
                            .method(api.getMethod())
                            .requestParams(invalidTypeParams)
                            .expectedStatusCode(400)
                            .expectedResponse("参数类型错误")
                            .description("参数" + param.getName() + "类型不匹配(期望" + param.getType() + ")，期望返回400")
                            .build());
                }
            }
        }

        // 3. 资源不存在 → 404
        if (api.getPath().contains("{")) {
            Map<String, Object> notFoundParams = generateValidParams(api);
            // 替换路径参数为不存在的ID
            for (Parameter param : api.getParameters() != null ? api.getParameters() : List.<Parameter>of()) {
                if ("path".equals(param.getIn())) {
                    notFoundParams.put(param.getName(), "999999999");
                }
            }
            cases.add(TestCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name("异常场景-资源不存在: " + api.getMethod() + " " + api.getPath())
                    .apiPath(api.getPath())
                    .method(api.getMethod())
                    .requestParams(notFoundParams)
                    .expectedStatusCode(404)
                    .expectedResponse("资源不存在")
                    .description("请求不存在的资源，期望返回404")
                    .build());
        }

        // 4. 未授权访问 → 401（契约驱动：文档未声明 401 的接口，其鉴权行为
        //    取决于全局安全配置而非该接口契约，无差别生成只会制造误报）
        if (api.declaredStatusCodes().contains(401)) {
            cases.add(TestCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name("异常场景-未授权访问: " + api.getMethod() + " " + api.getPath())
                    .apiPath(api.getPath())
                    .method(api.getMethod())
                    .requestParams(null)
                    .expectedStatusCode(401)
                    .expectedResponse("未授权")
                    .description("不携带认证信息访问，期望返回401")
                    .build());
        }

        // 5. 禁止访问 → 403（同上，契约驱动）
        if (api.declaredStatusCodes().contains(403)) {
            cases.add(TestCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name("异常场景-权限不足: " + api.getMethod() + " " + api.getPath())
                    .apiPath(api.getPath())
                    .method(api.getMethod())
                    .requestParams(null)
                    .expectedStatusCode(403)
                    .expectedResponse("权限不足")
                    .description("使用低权限用户访问，期望返回403")
                    .build());
        }

        // 6. 请求方法不允许 → 405（协议层行为，仅文档显式声明时才验证）
        if (api.declaredStatusCodes().contains(405)) {
            cases.add(TestCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name("异常场景-方法不允许: " + api.getMethod() + " " + api.getPath())
                    .apiPath(api.getPath())
                    .method("OPTIONS")
                    .requestParams(null)
                    .expectedStatusCode(405)
                    .expectedResponse("方法不允许")
                    .description("使用不支持的HTTP方法，期望返回405")
                    .build());
        }

        // 期望值契约对齐：文档声明了对应错误码 → 精确断言，否则降级 4xx 弱断言
        List<TestCase> aligned = contractGuard.align(cases, api);
        log.info("异常场景测试用例生成完成，共{}条（契约对齐后）", aligned.size());
        return aligned;
    }

    // ======================== 边界场景测试用例生成 ========================

    /**
     * 生成边界场景测试用例
     * 覆盖：空值、超长字符串、特殊字符、数值极值
     */
    public List<TestCase> generateBoundaryCases(ApiDefinition api) {
        log.info("生成边界场景测试用例: {} {}", api.getMethod(), api.getPath());
        List<TestCase> cases = new ArrayList<>();

        if (api.getParameters() == null || api.getParameters().isEmpty()) {
            return cases;
        }

        for (Parameter param : api.getParameters()) {
            if ("body".equals(param.getIn())) continue;

            // 1. 空值边界
            Map<String, Object> emptyParams = generateValidParams(api);
            emptyParams.put(param.getName(), "");
            cases.add(TestCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name("边界场景-空值[" + param.getName() + "]: " + api.getMethod() + " " + api.getPath())
                    .apiPath(api.getPath())
                    .method(api.getMethod())
                    .requestParams(emptyParams)
                    .expectedStatusCode(param.isRequired() ? 400 : 200)
                    .expectedResponse(param.isRequired() ? "参数不能为空" : "成功响应")
                    .description("参数" + param.getName() + "传入空字符串，期望" + (param.isRequired() ? "400" : "200"))
                    .build());

            // 2. 超长字符串边界
            if ("string".equalsIgnoreCase(param.getType())) {
                Map<String, Object> longParams = generateValidParams(api);
                longParams.put(param.getName(), "a".repeat(10001));
                cases.add(TestCase.builder()
                        .id(UUID.randomUUID().toString())
                        .name("边界场景-超长字符串[" + param.getName() + "]: " + api.getMethod() + " " + api.getPath())
                        .apiPath(api.getPath())
                        .method(api.getMethod())
                        .requestParams(longParams)
                        .expectedStatusCode(400)
                        .expectedResponse("参数长度超限")
                        .description("参数" + param.getName() + "传入10001字符超长字符串，期望返回400")
                        .build());
            }

            // 3. 特殊字符注入
            if ("string".equalsIgnoreCase(param.getType())) {
                String[] specialChars = {
                        "<script>alert('xss')</script>",
                        "'; DROP TABLE users; --",
                        "${7*7}",
                        "../../../etc/passwd",
                        "\u0000\uFFFF"
                };
                for (String special : specialChars) {
                    Map<String, Object> specialParams = generateValidParams(api);
                    specialParams.put(param.getName(), special);
                    cases.add(TestCase.builder()
                            .id(UUID.randomUUID().toString())
                            .name("边界场景-特殊字符[" + param.getName() + "]: " + api.getMethod() + " " + api.getPath())
                            .apiPath(api.getPath())
                            .method(api.getMethod())
                            .requestParams(specialParams)
                            .expectedStatusCode(400)
                            .expectedResponse("参数包含非法字符")
                            .description("参数" + param.getName() + "传入特殊字符，期望被拦截返回400")
                            .build());
                }
            }

            // 4. 数值极值边界
            if (List.of("integer", "int", "long", "number", "double", "float").contains(param.getType().toLowerCase())) {
                // 最小值/负数
                Map<String, Object> minParams = generateValidParams(api);
                minParams.put(param.getName(), -1);
                cases.add(TestCase.builder()
                        .id(UUID.randomUUID().toString())
                        .name("边界场景-数值负数[" + param.getName() + "]: " + api.getMethod() + " " + api.getPath())
                        .apiPath(api.getPath())
                        .method(api.getMethod())
                        .requestParams(minParams)
                        .expectedStatusCode(400)
                        .expectedResponse("参数值不合法")
                        .description("参数" + param.getName() + "传入负数，期望返回400")
                        .build());

                // 零值
                Map<String, Object> zeroParams = generateValidParams(api);
                zeroParams.put(param.getName(), 0);
                cases.add(TestCase.builder()
                        .id(UUID.randomUUID().toString())
                        .name("边界场景-数值零值[" + param.getName() + "]: " + api.getMethod() + " " + api.getPath())
                        .apiPath(api.getPath())
                        .method(api.getMethod())
                        .requestParams(zeroParams)
                        .expectedStatusCode(200)
                        .expectedResponse("成功响应")
                        .description("参数" + param.getName() + "传入0，期望返回200")
                        .build());

                // 最大值溢出
                Map<String, Object> maxParams = generateValidParams(api);
                maxParams.put(param.getName(), Long.MAX_VALUE);
                cases.add(TestCase.builder()
                        .id(UUID.randomUUID().toString())
                        .name("边界场景-数值溢出[" + param.getName() + "]: " + api.getMethod() + " " + api.getPath())
                        .apiPath(api.getPath())
                        .method(api.getMethod())
                        .requestParams(maxParams)
                        .expectedStatusCode(400)
                        .expectedResponse("参数值超限")
                        .description("参数" + param.getName() + "传入Long.MAX_VALUE，期望返回400")
                        .build());
            }

            // 5. 布尔值参数边界
            if ("boolean".equalsIgnoreCase(param.getType())) {
                Map<String, Object> boolParams = generateValidParams(api);
                boolParams.put(param.getName(), "not_a_boolean");
                cases.add(TestCase.builder()
                        .id(UUID.randomUUID().toString())
                        .name("边界场景-非法布尔值[" + param.getName() + "]: " + api.getMethod() + " " + api.getPath())
                        .apiPath(api.getPath())
                        .method(api.getMethod())
                        .requestParams(boolParams)
                        .expectedStatusCode(400)
                        .expectedResponse("参数类型错误")
                        .description("参数" + param.getName() + "传入非布尔值，期望返回400")
                        .build());
            }
        }

        // 期望值契约对齐：正向锚定声明成功码，负向精确或降级 4xx 弱断言
        List<TestCase> aligned = contractGuard.align(cases, api);
        log.info("边界场景测试用例生成完成，共{}条（契约对齐后）", aligned.size());
        return aligned;
    }

    // ======================== 覆盖率分析 ========================

    /**
     * 统计各场景的覆盖情况，返回覆盖率分析报告
     */
    public Map<String, Object> analyzeCoverage(ApiDefinition api, List<TestCase> testCases) {
        log.info("分析覆盖率: {} {}", api.getMethod(), api.getPath());

        Map<String, Object> coverage = new LinkedHashMap<>();

        // 按场景分类统计
        long normalCount = testCases.stream().filter(tc -> tc.getName() != null && tc.getName().startsWith("正常场景")).count();
        long errorCount = testCases.stream().filter(tc -> tc.getName() != null && tc.getName().startsWith("异常场景")).count();
        long boundaryCount = testCases.stream().filter(tc -> tc.getName() != null && tc.getName().startsWith("边界场景")).count();
        long otherCount = testCases.size() - normalCount - errorCount - boundaryCount;

        coverage.put("api", api.getMethod() + " " + api.getPath());
        coverage.put("summary", api.getSummary());
        coverage.put("totalTestCases", testCases.size());

        // 场景分布
        Map<String, Long> scenarioDistribution = new LinkedHashMap<>();
        scenarioDistribution.put("正常场景", normalCount);
        scenarioDistribution.put("异常场景", errorCount);
        scenarioDistribution.put("边界场景", boundaryCount);
        scenarioDistribution.put("其他场景", otherCount);
        coverage.put("scenarioDistribution", scenarioDistribution);

        // 参数覆盖度
        int totalParams = api.getParameters() != null ? (int) api.getParameters().stream()
                .filter(p -> !"body".equals(p.getIn())).count() : 0;
        long coveredParams = 0;
        if (api.getParameters() != null && !testCases.isEmpty()) {
            coveredParams = api.getParameters().stream()
                    .filter(p -> !"body".equals(p.getIn()))
                    .filter(p -> testCases.stream()
                            .anyMatch(tc -> tc.getRequestParams() != null && tc.getRequestParams().containsKey(p.getName())))
                    .count();
        }
        double paramCoverage = totalParams > 0 ? (double) coveredParams / totalParams * 100 : 100;
        coverage.put("parameterCoverage", String.format("%.1f%%", paramCoverage));
        coverage.put("coveredParameters", coveredParams);
        coverage.put("totalParameters", totalParams);

        // 响应码覆盖度
        int totalResponseCodes = api.getResponses() != null ? api.getResponses().size() : 0;
        long coveredResponseCodes = 0;
        if (api.getResponses() != null) {
            coveredResponseCodes = api.getResponses().keySet().stream()
                    .filter(code -> testCases.stream()
                            .anyMatch(tc -> String.valueOf(tc.getExpectedStatusCode()).equals(code)
                                    || ("default".equals(code) && tc.getExpectedStatusCode() >= 500)))
                    .count();
        }
        double responseCoverage = totalResponseCodes > 0 ? (double) coveredResponseCodes / totalResponseCodes * 100 : 0;
        coverage.put("responseCodeCoverage", String.format("%.1f%%", responseCoverage));
        coverage.put("coveredResponseCodes", coveredResponseCodes);
        coverage.put("totalResponseCodes", totalResponseCodes);

        // 场景覆盖评估
        boolean hasNormal = normalCount > 0;
        boolean hasError = errorCount > 0;
        boolean hasBoundary = boundaryCount > 0;
        String coverageLevel;
        if (hasNormal && hasError && hasBoundary) {
            coverageLevel = "FULL";
        } else if (hasNormal && (hasError || hasBoundary)) {
            coverageLevel = "PARTIAL";
        } else {
            coverageLevel = "LOW";
        }
        coverage.put("coverageLevel", coverageLevel);

        log.info("覆盖率分析完成: 参数覆盖{}, 响应码覆盖{}, 场景覆盖等级={}",
                coverage.get("parameterCoverage"), coverage.get("responseCodeCoverage"), coverageLevel);
        return coverage;
    }

    // ======================== AI驱动的综合测试用例生成 ========================

    /**
     * Generate test cases using Spring AI OpenAI for intelligent test generation.
     * Falls back to basic generation if AI call fails.
     *
     * <p>AI 产出必须通过 {@link TestCaseContractGuard#guardAiCase} 契约守卫：
     * 幻觉路径/方法/参数直接拦截，无契约背书的状态码降级弱断言或丢弃。
     * 守卫后一条不剩时回退规则生成，保证链路总有契约合法的用例产出。
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
                List<TestCase> guarded = new ArrayList<>();
                for (TestCase tc : aiTestCases) {
                    contractGuard.guardAiCase(tc, api).ifPresent(guarded::add);
                }

                int hallucinated = aiTestCases.size() - guarded.size();
                if (hallucinated > 0) {
                    log.warn("[Schema-Guard] Rejected {}/{} AI cases for {} {} (hallucinated path/params/status)",
                            hallucinated, aiTestCases.size(), api.getMethod(), api.getPath());
                }

                if (!guarded.isEmpty()) {
                    log.info("AI generated {} contract-valid test cases for {} {}",
                            guarded.size(), api.getMethod(), api.getPath());
                    return guarded;
                }
                log.warn("All AI cases rejected by contract guard, falling back to rule-based generation");
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

                // 状态码解析失败的行直接丢弃：静默默认 200 会把坏行变成
                // "期望 200"的假用例，正是期望值不可信的来源之一。
                // 后续契约守卫还会再校验一次状态码是否有文档背书。
                int expectedStatus;
                try {
                    expectedStatus = Integer.parseInt(parts[4].trim());
                } catch (NumberFormatException e) {
                    log.warn("Dropped malformed AI line (non-numeric status): {}", line);
                    continue;
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

        // 降级路径同样经契约对齐，保证任何分支产出的期望值都可追溯到文档
        return contractGuard.align(testCases, api);
    }

    private Map<String, Object> generateRequiredParams(ApiDefinition api) {
        Map<String, Object> params = new HashMap<>();
        if (api.getParameters() != null) {
            for (Parameter param : api.getParameters()) {
                if ("body".equals(param.getIn())) continue;
                if (param.isRequired()) {
                    params.put(param.getName(), generateDefaultValue(param.getType()));
                }
            }
        }
        return params;
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

    /**
     * 根据参数类型返回类型不匹配的测试值
     */
    private String getTypeMismatchValue(String type) {
        if (type == null) return null;
        return switch (type.toLowerCase()) {
            case "integer", "int", "long", "number", "double", "float" -> "not_a_number";
            case "boolean" -> "not_a_boolean";
            case "string" -> null; // string类型不存在类型不匹配
            default -> null;
        };
    }
}