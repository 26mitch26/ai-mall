package com.ai.mall.agent.test.service.mcp;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.McpTestRun;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.service.report.TestReportStore;
import com.ai.mall.agent.test.service.security.TestAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 测试 Agent 的 MCP 协议实现（Streamable HTTP JSON-only，协议版本 2025-06-18）。
 *
 * <p>实现 initialize / ping / initialized 通知 / tools/list / tools/call；
 * GET-SSE 事件流、会话恢复、resources、prompts、sampling 均不在本 profile 内。
 * 与 {@code agent-customer} 的 MCP 端点关键差异：**协议入口即 fail-closed**——
 * 这里不放进网关白名单，凭证由 {@link TestAccessGuard} 在进入协议层之前校验。
 * 原因是客服侧最坏是"读了别人的订单"，而这里最坏是"以服务身份对内网系统发任意请求"。
 *
 * <p>工具全部返回 JSON 文本（{@code content[].text}），并遵守"失败即 isError"，
 * 让 Agent 能区分"协议/参数错误"与"测试跑出了红灯"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpProtocolService {

    public static final String PROTOCOL_VERSION = "2025-06-18";

    private static final String TOOL_LIST_MODULES = "list_test_modules";
    private static final String TOOL_RUN_TESTS = "run_tests";
    private static final String TOOL_RUN_STATUS = "get_run_status";
    private static final String TOOL_REPORT = "get_test_report";
    private static final String TOOL_LIST_REPORTS = "list_test_reports";

    private static final Set<String> TOOLS = Set.of(TOOL_LIST_MODULES, TOOL_RUN_TESTS, TOOL_RUN_STATUS,
            TOOL_REPORT, TOOL_LIST_REPORTS);

    private final ObjectMapper objectMapper;
    private final AgentTestConfig config;
    private final TestAccessGuard accessGuard;
    private final McpRunRegistry runRegistry;
    private final TestReportStore reportStore;
    private final TestReportPresenter presenter;

    public ObjectNode handle(JsonNode message, String caller) {
        if (message == null || !message.isObject()) {
            return error(null, -32600, "Invalid Request");
        }
        JsonNode id = message.get("id");
        JsonNode jsonrpc = message.get("jsonrpc");
        JsonNode methodNode = message.get("method");
        if (jsonrpc == null || !jsonrpc.isTextual() || !"2.0".equals(jsonrpc.asText())
                || methodNode == null || !methodNode.isTextual() || methodNode.asText().isBlank()) {
            return error(id, -32600, "Invalid Request");
        }
        if (id != null && !id.isNull() && !id.isTextual() && !id.isIntegralNumber() && !id.isFloatingPointNumber()) {
            return error(null, -32600, "Invalid Request");
        }
        String method = methodNode.asText();
        if (id == null) {
            // 通知类消息不返回 JSON-RPC 响应
            return null;
        }
        JsonNode params = message.path("params");
        if (!params.isMissingNode() && !params.isObject()) {
            return error(id, -32602, "Invalid params");
        }
        return switch (method) {
            case "initialize" -> initialize(id, params);
            case "ping" -> success(id, objectMapper.createObjectNode());
            case "tools/list" -> params.has("cursor")
                    ? error(id, -32602, "Pagination cursors are not supported by this profile")
                    : toolsList(id);
            case "tools/call" -> toolsCall(id, params, caller == null ? "unknown" : caller);
            default -> error(id, -32601, "Method not found");
        };
    }

    private ObjectNode initialize(JsonNode id, JsonNode params) {
        JsonNode protocolVersion = params.path("protocolVersion");
        JsonNode clientInfo = params.path("clientInfo");
        if (!protocolVersion.isTextual() || !protocolVersion.asText().matches("\\d{4}-\\d{2}-\\d{2}")
                || !params.path("capabilities").isObject()
                || !clientInfo.isObject() || !clientInfo.path("name").isTextual()
                || !clientInfo.path("version").isTextual()
                || clientInfo.path("name").asText().isBlank() || clientInfo.path("version").asText().isBlank()) {
            return error(id, -32602, "Invalid initialize params");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.set("capabilities", objectMapper.createObjectNode().set("tools",
                objectMapper.createObjectNode().put("listChanged", false)));
        result.set("serverInfo", objectMapper.createObjectNode()
                .put("name", "ai-mall-test-agent")
                .put("version", "1.0.0"));
        result.put("instructions", """
                Automated testing agent for the AI-Mall services. Typical loop: list_test_modules -> run_tests (returns a runId immediately) \
                -> get_run_status until finished -> get_test_report for failures. A run with failedTests>0 is a normal outcome, not a tool error. \
                Contract tests assert HTTP status/JSON schema/business-code/pagination invariants; the agent-customer-scenarios suite asserts \
                conversation-level behavior (intent routing, tool grounding, refusal and auth boundaries).""");
        return success(id, result);
    }

    private ObjectNode toolsList(JsonNode id) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (String name : toolOrder()) {
            tools.add(toolDefinition(name));
        }
        return success(id, result);
    }

    private List<String> toolOrder() {
        return List.of(TOOL_LIST_MODULES, TOOL_RUN_TESTS, TOOL_RUN_STATUS, TOOL_REPORT, TOOL_LIST_REPORTS);
    }

    private ObjectNode toolDefinition(String name) {
        ObjectNode tool = objectMapper.createObjectNode().put("name", name);
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        switch (name) {
            case TOOL_LIST_MODULES -> {
                tool.put("description", "List testable modules with their base URLs, safe-demo flag and AI generation status.");
            }
            case TOOL_RUN_TESTS -> {
                tool.put("description", "Start a test run for one module. Returns immediately with a runId because a full run can take minutes; "
                        + "poll get_run_status, then read get_test_report. waitSeconds>0 blocks up to that many seconds for completion.");
                properties.set("module", stringSchema("Module name from list_test_modules", 1, 64));
                required.add("module");
                properties.set("waitSeconds", integerSchema("Maximum seconds to wait inline (0 = return immediately)",
                        0, Math.max(1, config.getMcp().getMaxWaitSeconds())));
            }
            case TOOL_RUN_STATUS -> {
                tool.put("description", "Get the status of a previously started run, including its reportId when finished.");
                properties.set("runId", stringSchema("Run identifier returned by run_tests", 36, 36));
                required.add("runId");
            }
            case TOOL_REPORT -> {
                tool.put("description", "Read a test report. Defaults to summary plus failed cases with their failed assertions; "
                        + "set includeResults=true for all test cases (truncated).");
                properties.set("reportId", stringSchema("Report identifier", 36, 36));
                required.add("reportId");
                properties.set("includeResults", booleanSchema("Include every test case detail"));
                properties.set("maxResults", integerSchema("Maximum number of test cases to return",
                        1, Math.max(1, config.getMcp().getMaxReportResults())));
            }
            case TOOL_LIST_REPORTS -> {
                tool.put("description", "List stored test reports, newest first, so a reportId can be discovered without a run.");
                properties.set("limit", integerSchema("Maximum number of reports", 1, 50));
            }
            default -> throw new IllegalStateException("Unknown tool definition: " + name);
        }
        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode toolsCall(JsonNode id, JsonNode params, String caller) {
        JsonNode nameNode = params.path("name");
        JsonNode arguments = params.path("arguments");
        if (!nameNode.isTextual() || !arguments.isObject()) {
            return error(id, -32602, "Invalid params");
        }
        String name = nameNode.asText();
        if (!TOOLS.contains(name)) {
            return error(id, -32602, "Unknown tool");
        }
        String validationError = validateArguments(name, arguments);
        if (validationError != null) {
            return error(id, -32602, validationError);
        }
        log.info("[mcp] tool call: tool={} caller={} arguments={}", name, caller, arguments);
        try {
            ObjectNode payload = switch (name) {
                case TOOL_LIST_MODULES -> listModules();
                case TOOL_RUN_TESTS -> runTests(arguments, caller);
                case TOOL_RUN_STATUS -> runStatus(arguments);
                case TOOL_REPORT -> report(arguments);
                case TOOL_LIST_REPORTS -> listReports(arguments);
                default -> throw new IllegalStateException("Unhandled tool: " + name);
            };
            return toolResult(id, objectMapper.writeValueAsString(payload), false);
        } catch (McpRunRejectedException ex) {
            return toolErrorResult(id, ex.getMessage());
        } catch (ResponseStatusException ex) {
            // 例如 module 不在白名单：属于调用方参数问题，按协议返回 Invalid params 而不是工具错误
            return error(id, -32602, ex.getReason() == null ? "Invalid params" : ex.getReason());
        } catch (IllegalArgumentException ex) {
            return error(id, -32602, ex.getMessage());
        } catch (Exception ex) {
            log.error("[mcp] tool failed: tool={} caller={}", name, caller, ex);
            return toolErrorResult(id, "Tool execution failed: " + ex.getClass().getSimpleName());
        }
    }

    private ObjectNode listModules() {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode modules = node.putArray("modules");
        for (String module : accessGuard.supportedModules()) {
            ObjectNode entry = modules.addObject();
            entry.put("name", module);
            entry.put("kind", moduleKind(module));
            String baseUrl = config.getModuleBaseUrls() == null
                    ? null
                    : config.getModuleBaseUrls().get(module);
            if (baseUrl == null && !isConversationalModule(module)) {
                baseUrl = config.getBaseUrl();
            }
            if (baseUrl != null) {
                entry.put("baseUrl", baseUrl);
            }
        }
        node.put("safeDemoMode", config.isSafeDemoMode());
        node.put("aiCaseGenerationEnabled", config.getAi().isEnabled());
        node.put("model", config.getAi().getModel());
        node.put("maxApisPerRun", config.getMaxApisPerRun());
        node.put("maxConcurrentRuns", config.getMcp().getMaxConcurrentRuns());
        node.put("responseTimeThresholdMs", config.getResponseTimeThresholdMs());
        return node;
    }

    private boolean isConversationalModule(String module) {
        return com.ai.mall.agent.test.service.scenario.CustomerScenarioRunner.MODULE_NAME.equals(module)
                || com.ai.mall.agent.test.service.quality.AgentQualityEvaluationRunner.MODULE_NAME.equals(module);
    }

    private String moduleKind(String module) {
        if (com.ai.mall.agent.test.service.quality.AgentQualityEvaluationRunner.MODULE_NAME.equals(module)) {
            return "agent-quality-evaluation";
        }
        return isConversationalModule(module) ? "conversation-scenarios" : "openapi-contract";
    }

    private ObjectNode runTests(JsonNode arguments, String caller) {
        String module = arguments.path("module").asText();
        // 白名单再校验一次：MCP 是新增的高危入口，不能只依赖 REST 层的校验
        accessGuard.validateModule(module);
        int waitSeconds = arguments.has("waitSeconds")
                ? arguments.path("waitSeconds").asInt()
                : 0;
        McpTestRun run = runRegistry.submit(module, caller);
        boolean completed = runRegistry.awaitCompletion(run, waitSeconds);
        ObjectNode node = runNode(run);
        if (!completed) {
            node.put("hint", "Run is still in progress. Poll get_run_status with this runId, then read get_test_report.");
        }
        return node;
    }

    private ObjectNode runStatus(JsonNode arguments) {
        McpTestRun run = runRegistry.find(arguments.path("runId").asText());
        if (run == null) {
            throw new IllegalArgumentException("Unknown runId (it may have been evicted or the service restarted)");
        }
        return runNode(run);
    }

    private ObjectNode report(JsonNode arguments) {
        String reportId = arguments.path("reportId").asText();
        TestReport report = reportStore.findById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("Unknown reportId (reports are kept in memory and lost on restart)");
        }
        boolean includeResults = arguments.path("includeResults").asBoolean(false);
        int maxResults = arguments.has("maxResults")
                ? Math.min(arguments.path("maxResults").asInt(), Math.max(1, config.getMcp().getMaxReportResults()))
                : Math.max(1, config.getMcp().getMaxReportResults());
        return presenter.detail(report, maxResults, includeResults);
    }

    private ObjectNode listReports(JsonNode arguments) {
        int limit = arguments.has("limit") ? arguments.path("limit").asInt() : 10;
        List<TestReport> reports = new ArrayList<>(reportStore.findAllAsList());
        reports.sort((left, right) -> {
            if (left.getStartTime() == null || right.getStartTime() == null) {
                return 0;
            }
            return right.getStartTime().compareTo(left.getStartTime());
        });
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode items = node.putArray("reports");
        for (TestReport report : reports.stream().limit(limit).toList()) {
            items.add(presenter.brief(report));
        }
        node.put("total", reportStore.count());
        return node;
    }

    private ObjectNode runNode(McpTestRun run) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("runId", run.getRunId());
        node.put("module", run.getModule());
        node.put("status", run.getStatus().name());
        node.put("startedAt", run.getStartedAt().toString());
        if (run.getFinishedAt() != null) {
            node.put("finishedAt", run.getFinishedAt().toString());
        }
        if (run.getReportId() != null) {
            node.put("reportId", run.getReportId());
        }
        if (run.getTotalTests() != null) {
            node.put("totalTests", run.getTotalTests());
        }
        if (run.getFailedTests() != null) {
            node.put("failedTests", run.getFailedTests());
        }
        if (run.getError() != null) {
            node.put("error", run.getError());
        }
        return node;
    }

    private String validateArguments(String name, JsonNode args) {
        List<String> allowed = switch (name) {
            case TOOL_LIST_MODULES -> List.of();
            case TOOL_RUN_TESTS -> List.of("module", "waitSeconds");
            case TOOL_RUN_STATUS -> List.of("runId");
            case TOOL_REPORT -> List.of("reportId", "includeResults", "maxResults");
            case TOOL_LIST_REPORTS -> List.of("limit");
            default -> List.of();
        };
        var fields = args.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                return "Unexpected argument";
            }
        }
        switch (name) {
            case TOOL_RUN_TESTS -> {
                JsonNode module = args.path("module");
                if (!module.isTextual() || !module.asText().matches("[A-Za-z0-9_-]{1,64}")) {
                    return "module must match [A-Za-z0-9_-]{1,64}";
                }
                if (args.has("waitSeconds") && (!args.path("waitSeconds").isIntegralNumber()
                        || args.path("waitSeconds").asInt() < 0
                        || args.path("waitSeconds").asInt() > Math.max(1, config.getMcp().getMaxWaitSeconds()))) {
                    return "waitSeconds must be an integer from 0 to " + Math.max(1, config.getMcp().getMaxWaitSeconds());
                }
            }
            case TOOL_RUN_STATUS -> {
                if (!args.path("runId").isTextual() || !args.path("runId").asText().matches("[A-Za-z0-9-]{1,64}")) {
                    return "runId is invalid";
                }
            }
            case TOOL_REPORT -> {
                if (!args.path("reportId").isTextual() || !args.path("reportId").asText().matches("[A-Za-z0-9-]{1,64}")) {
                    return "reportId is invalid";
                }
                if (args.has("includeResults") && !args.path("includeResults").isBoolean()) {
                    return "includeResults must be a boolean";
                }
                if (args.has("maxResults") && (!args.path("maxResults").isIntegralNumber()
                        || args.path("maxResults").asInt() < 1
                        || args.path("maxResults").asInt() > Math.max(1, config.getMcp().getMaxReportResults()))) {
                    return "maxResults must be an integer from 1 to " + Math.max(1, config.getMcp().getMaxReportResults());
                }
            }
            case TOOL_LIST_REPORTS -> {
                if (args.has("limit") && (!args.path("limit").isIntegralNumber()
                        || args.path("limit").asInt() < 1 || args.path("limit").asInt() > 50)) {
                    return "limit must be an integer from 1 to 50";
                }
            }
            default -> {
                // list_test_modules has no arguments
            }
        }
        return null;
    }

    private ObjectNode stringSchema(String description, int minLength, int maxLength) {
        return objectMapper.createObjectNode().put("type", "string").put("description", description)
                .put("minLength", minLength).put("maxLength", maxLength);
    }

    private ObjectNode integerSchema(String description, int minimum, int maximum) {
        return objectMapper.createObjectNode().put("type", "integer").put("description", description)
                .put("minimum", minimum).put("maximum", maximum);
    }

    private ObjectNode booleanSchema(String description) {
        return objectMapper.createObjectNode().put("type", "boolean").put("description", description);
    }

    private ObjectNode success(JsonNode id, ObjectNode result) {
        ObjectNode response = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id.deepCopy());
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id == null ? objectMapper.nullNode() : id.deepCopy());
        response.set("error", objectMapper.createObjectNode().put("code", code).put("message", message));
        return response;
    }

    private ObjectNode toolResult(JsonNode id, String text, boolean isError) {
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", isError);
        return success(id, result);
    }

    private ObjectNode toolErrorResult(JsonNode id, String message) {
        return toolResult(id, message, true);
    }
}
