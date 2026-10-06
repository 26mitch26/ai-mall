package com.ai.mall.agent.customer.service.mcp;

import com.ai.mall.agent.customer.model.AuditEvent;
import com.ai.mall.agent.customer.model.AuditEventType;
import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.model.workflow.AfterSaleWorkflowState;
import com.ai.mall.agent.customer.service.audit.AuditService;
import com.ai.mall.agent.customer.service.tool.ToolRegistry;
import com.ai.mall.agent.customer.service.workflow.AfterSaleWorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Stateless JSON-only Streamable HTTP profile for MCP protocol version 2025-06-18.
 * Implements initialize, ping, initialized notifications, tools/list, and tools/call.
 * POST replies are JSON; GET/SSE streams, sessions/resumption, resources, prompts,
 * sampling, and later protocol versions are outside this profile.
 */
@Service
@RequiredArgsConstructor
public class McpProtocolService {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Set<String> PRIVATE_TOOLS = Set.of("get_order_info", "prepare_after_sale");

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final AuditService auditService;
    private final AfterSaleWorkflowService afterSaleWorkflowService;

    public ObjectNode handle(JsonNode message, ToolInvocationContext context) {
        if (!message.isObject()) {
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
        boolean notification = id == null;
        if (notification) {
            if ("notifications/initialized".equals(method)) return null;
            // MCP notifications do not receive JSON-RPC responses.
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
                    : toolsList(id, context);
            case "tools/call" -> toolsCall(id, params, context);
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
                .put("name", "ai-mall-customer")
                .put("version", "1.0.0"));
        result.put("instructions", "Stateless JSON-only Streamable HTTP profile: tools are authorization-scoped; private tools require a valid Authorization bearer token.");
        return success(id, result);
    }

    private ObjectNode toolsList(JsonNode id, ToolInvocationContext context) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        boolean authenticated = context != null && context.isAuthenticated();
        ObjectNode searchSchema = schema();
        searchSchema.putArray("required").add("keyword");
        tools.add(tool("search_products", "Search public catalog products by keyword.", searchSchema));
        if (authenticated) {
            ObjectNode orderSchema = schema();
            orderSchema.putArray("required").add("order_sn");
            tools.add(tool("get_order_info", "Read an order belonging to the authenticated member.", orderSchema));
            ObjectNode afterSaleSchema = schema();
            ArrayNode required = afterSaleSchema.putArray("required");
            required.add("order_sn"); required.add("reason"); required.add("description");
            tools.add(tool("prepare_after_sale", "Create a durable after-sale draft for review. This does not submit a return or refund.", afterSaleSchema));
        }
        return success(id, result);
    }

    private ObjectNode toolsCall(JsonNode id, JsonNode params, ToolInvocationContext context) {
        JsonNode nameNode = params.path("name");
        JsonNode arguments = params.path("arguments");
        if (!nameNode.isTextual() || !arguments.isObject()) return error(id, -32602, "Invalid params");
        String name = nameNode.asText();
        if (!("search_products".equals(name) || PRIVATE_TOOLS.contains(name))) {
            return error(id, -32602, "Unknown tool");
        }
        String sessionId = context == null ? UUID.randomUUID().toString() : context.getSessionId();
        String memberId = context == null ? null : context.getMemberId();
        if (PRIVATE_TOOLS.contains(name) && (context == null || !context.isAuthenticated())) {
            record(sessionId, memberId, AuditEventType.GUARDRAIL_BLOCK, "MCP private tool denied: " + name, true);
            return toolError(id, "This tool requires a valid authenticated member token.");
        }

        String validationError = validateArguments(name, arguments);
        if (validationError != null) return error(id, -32602, validationError);

        record(sessionId, memberId, AuditEventType.TOOL_CALL, "MCP tool call: " + name, false);
        try {
            String output;
            if ("prepare_after_sale".equals(name)) {
                AfterSaleWorkflowState state = afterSaleWorkflowService.prepare(context,
                        arguments.path("order_sn").asText(), arguments.path("reason").asText(),
                        arguments.path("description").asText());
                ObjectNode draft = objectMapper.createObjectNode()
                        .put("taskId", state.getTaskId())
                        .put("status", state.getStatus())
                        .put("orderSn", state.getOrderSn())
                        .put("reason", state.getReason())
                        .put("draft", state.getDraft())
                        .put("version", state.getVersion());
                draft.put("orderSummary", state.getOrderSummary());
                if (state.getCreatedAt() != null) draft.put("createdAt", state.getCreatedAt().toString());
                if (state.getUpdatedAt() != null) draft.put("updatedAt", state.getUpdatedAt().toString());
                output = objectMapper.writeValueAsString(draft);
            } else {
                output = toolRegistry.executeStructuredTool(name, objectMapper.writeValueAsString(arguments), context);
            }
            boolean failed = isToolError(output);
            record(sessionId, memberId, failed ? AuditEventType.GUARDRAIL_BLOCK : AuditEventType.TOOL_RESULT,
                    "MCP tool completed: " + name, failed);
            return toolResult(id, output == null ? "Tool execution failed." : output, failed);
        } catch (Exception ex) {
            record(sessionId, memberId, AuditEventType.GUARDRAIL_BLOCK, "MCP tool failed: " + name, true);
            return toolError(id, "Tool execution failed. Please try again.");
        }
    }

    private String validateArguments(String name, JsonNode args) {
        Set<String> allowed = switch (name) {
            case "search_products" -> Set.of("keyword", "page", "pageSize");
            case "get_order_info" -> Set.of("order_sn");
            case "prepare_after_sale" -> Set.of("order_sn", "reason", "description");
            default -> Set.of();
        };
        var fields = args.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) return "Unexpected argument";
        if ("search_products".equals(name)) {
            if (!boundedText(args.path("keyword"), 1, 100)) return "keyword must contain 1 to 100 characters";
            if (args.has("page") && (!args.path("page").isIntegralNumber() || args.path("page").asInt() < 1 || args.path("page").asInt() > 10000)) return "page must be an integer from 1 to 10000";
            if (args.has("pageSize") && (!args.path("pageSize").isIntegralNumber() || args.path("pageSize").asInt() < 1 || args.path("pageSize").asInt() > 50)) return "pageSize must be an integer from 1 to 50";
        } else if ("get_order_info".equals(name)) {
            if (!args.path("order_sn").isTextual() || !args.path("order_sn").asText().matches("[A-Za-z0-9_-]{1,64}")) return "order_sn is invalid";
        } else if ("prepare_after_sale".equals(name)) {
            if (!args.path("order_sn").isTextual() || !args.path("order_sn").asText().matches("[A-Za-z0-9_-]{1,64}")) return "order_sn is invalid";
            if (!boundedText(args.path("reason"), 1, 200)) return "reason must contain 1 to 200 characters";
            if (!boundedText(args.path("description"), 1, 1000)) return "description must contain 1 to 1000 characters";
        }
        return null;
    }

    private boolean boundedText(JsonNode node, int min, int max) {
        return node != null && node.isTextual() && node.asText().trim().length() >= min && node.asText().length() <= max;
    }

    private ObjectNode schema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    private ObjectNode tool(String name, String description, ObjectNode schema) {
        ObjectNode properties = (ObjectNode) schema.path("properties");
        switch (name) {
            case "search_products" -> {
                properties.set("keyword", stringSchema("Product search keyword", 1, 100));
                properties.set("page", integerSchema("1-based page number", 1, 10000));
                properties.set("pageSize", integerSchema("Maximum number of results", 1, 50));
            }
            case "get_order_info" -> properties.set("order_sn", stringSchema("Order number", 1, 64));
            case "prepare_after_sale" -> {
                properties.set("order_sn", stringSchema("Order number", 1, 64));
                properties.set("reason", stringSchema("Reason for after-sale request", 1, 200));
                properties.set("description", stringSchema("Details for the draft", 1, 1000));
            }
        }
        return objectMapper.createObjectNode().put("name", name).put("description", description).set("inputSchema", schema);
    }

    private ObjectNode stringSchema(String description, int minLength, int maxLength) {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "string").put("description", description)
                .put("minLength", minLength).put("maxLength", maxLength);
        if ("Order number".equals(description)) schema.put("pattern", "^[A-Za-z0-9_-]{1,64}$");
        return schema;
    }

    private ObjectNode integerSchema(String description, int minimum, int maximum) {
        return objectMapper.createObjectNode().put("type", "integer").put("description", description)
                .put("minimum", minimum).put("maximum", maximum);
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
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

    private ObjectNode toolError(JsonNode id, String message) { return toolResult(id, message, true); }

    private boolean isToolError(String output) {
        if (output == null) return true;
        try {
            JsonNode result = objectMapper.readTree(output);
            return result.has("error") || result.path("blocked").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void record(String sessionId, String memberId, AuditEventType type, String detail, boolean blocked) {
        auditService.record(AuditEvent.builder().sessionId(sessionId).memberId(memberId)
                .type(type.getCode()).detail(detail).blocked(blocked).costMs(0).build());
    }
}
