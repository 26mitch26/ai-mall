package com.ai.mall.agent.test.controller;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.config.TestAccessInterceptor;
import com.ai.mall.agent.test.service.mcp.McpProtocolService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 测试 Agent 的 MCP 端点（Streamable HTTP JSON-only，协议版本 2025-06-18）。
 *
 * <p>与 {@code agent-customer} 的 MCP 端点的关键差异：<b>不进网关白名单</b>。
 * 客服侧最坏是"读了别人的订单"，测试侧最坏是"以服务身份对内网系统发任意请求"，
 * 所以这里要求 {@link com.ai.mall.agent.test.service.security.TestAccessGuard} 在进入协议层之前
 * 就完成凭证校验（由 {@link TestAccessInterceptor} 统一拦截 {@code /mcp}）。
 * 因此客户端必须携带用户 JWT（经网关时由网关校验、直连 8085 时由本服务校验），
 * 或配置 {@code test.agent.auth.token} 后携带 {@code X-Test-Agent-Token}。
 *
 * <p>GET 事件流按 profile 不支持；请求必须为 JSON 且 Accept 同时接受 JSON 与 event-stream。
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {

    private final ObjectMapper objectMapper;
    private final McpProtocolService protocolService;
    private final AgentTestConfig config;

    @PostMapping
    public ResponseEntity<?> post(@RequestBody(required = false) String body,
                                  @RequestHeader HttpHeaders headers,
                                  HttpServletRequest request,
                                  @RequestAttribute(value = TestAccessInterceptor.CALLER_ATTRIBUTE,
                                          required = false) String caller) {
        if (!config.getMcp().isEnabled()) {
            return jsonError(null, -32600, "MCP endpoint is disabled (test.agent.mcp.enabled=false)",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!isJsonContentType(headers.getContentType())) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }
        if (!supportsRequiredAcceptTypes(headers)) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
        }
        String origin = headers.getFirst("Origin");
        if (origin != null && !originAllowed(origin)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        JsonNode message;
        try {
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("empty body");
            }
            message = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (Exception e) {
            return jsonError(null, -32700, "Parse error", HttpStatus.BAD_REQUEST);
        }
        if (message == null || !message.isObject()) {
            return jsonError(null, -32600, "Invalid Request", HttpStatus.BAD_REQUEST);
        }

        boolean initialize = "initialize".equals(message.path("method").asText());
        if (!initialize) {
            String version = headers.getFirst("MCP-Protocol-Version");
            if (!McpProtocolService.PROTOCOL_VERSION.equals(version)) {
                return jsonError(message.path("id"), -32600,
                        "MCP-Protocol-Version must be " + McpProtocolService.PROTOCOL_VERSION,
                        HttpStatus.BAD_REQUEST);
            }
        }
        log.debug("[mcp] {} from caller={}", message.path("method").asText(), caller);
        var response = protocolService.handle(message, caller == null ? request.getRemoteAddr() : caller);
        if (response == null) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    private boolean isJsonContentType(MediaType contentType) {
        return contentType != null && (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || "application".equalsIgnoreCase(contentType.getType()) && contentType.getSubtype().endsWith("+json"));
    }

    private boolean supportsRequiredAcceptTypes(HttpHeaders headers) {
        return headers.getAccept().stream()
                .anyMatch(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON))
                && headers.getAccept().stream()
                .anyMatch(type -> type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    private boolean originAllowed(String origin) {
        String allowedOrigins = config.getMcp().getAllowedOrigins();
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return false;
        }
        Set<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toSet());
        return origins.contains(origin);
    }

    private ResponseEntity<JsonNode> jsonError(JsonNode id, int code, String message, HttpStatus status) {
        var error = objectMapper.createObjectNode().put("code", code).put("message", message);
        var response = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id == null || id.isMissingNode() ? objectMapper.nullNode() : id.deepCopy());
        response.set("error", error);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(response);
    }
}
