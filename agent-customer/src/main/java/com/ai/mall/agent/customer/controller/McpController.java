package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.service.mcp.McpProtocolService;
import com.ai.mall.agent.customer.service.security.MemberIdentityResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Streamable HTTP JSON-only MCP endpoint; the GET event stream is intentionally unsupported. */
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {

    private final ObjectMapper objectMapper;
    private final McpProtocolService protocolService;
    private final MemberIdentityResolver memberIdentityResolver;

    /** Exact browser origins allowed by deployment; requests without Origin are accepted. */
    @Value("${agent.mcp.allowed-origins:}")
    private String allowedOrigins;

    @PostMapping
    public ResponseEntity<?> post(@RequestBody(required = false) String body,
                                  @RequestHeader HttpHeaders headers) {
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
            if (body == null || body.isBlank()) throw new IllegalArgumentException("empty body");
            message = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (Exception e) {
            return jsonError(null, -32700, "Parse error", HttpStatus.BAD_REQUEST);
        }
        if (message == null || !message.isObject()) {
            return jsonError(null, -32600, "Invalid Request", HttpStatus.BAD_REQUEST);
        }

        String sessionId = java.util.UUID.randomUUID().toString();
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        ToolInvocationContext context;
        try {
            context = memberIdentityResolver.resolve(sessionId, authorization);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            // Identity resolution must fail closed for private tools.
            context = ToolInvocationContext.anonymous(sessionId);
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
        var response = protocolService.handle(message, context);
        if (response == null) return ResponseEntity.accepted().build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    private boolean isJsonContentType(MediaType contentType) {
        return contentType != null && (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || "application".equalsIgnoreCase(contentType.getType()) && contentType.getSubtype().endsWith("+json"));
    }

    private boolean supportsRequiredAcceptTypes(HttpHeaders headers) {
        List<MediaType> accepted = headers.getAccept();
        return accepted.stream().anyMatch(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON))
                && accepted.stream().anyMatch(type -> type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    private boolean originAllowed(String origin) {
        Set<String> origins = java.util.Arrays.stream(allowedOrigins.split(","))
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
