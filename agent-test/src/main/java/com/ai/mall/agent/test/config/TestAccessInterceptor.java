package com.ai.mall.agent.test.config;

import com.ai.mall.agent.test.service.security.TestAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 把 {@link TestAccessGuard} 落到所有对外入口上。
 *
 * <p>覆盖 {@code /api/v1/test/**}（前端页面与 CI 触发）与 {@code /mcp}（Agent 工具调用），
 * 两者共用同一套凭证校验，避免"REST 有鉴权、MCP 裸奔"的经典缺口。
 * SpringDoc 的 {@code /v3/api-docs} 不在拦截范围内。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestAccessInterceptor implements HandlerInterceptor {

    /** 通过鉴权后的调用方标识，便于审计日志关联。 */
    public static final String CALLER_ATTRIBUTE = "agentTest.caller";

    private final TestAccessGuard accessGuard;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String caller = accessGuard.authenticate(request).orElse(null);
        if (caller == null) {
            log.warn("Rejected unauthenticated call: {} {}", request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        String message = accessGuard.isAuthEnabled()
                ? "测试 Agent 需要有效凭证（用户 JWT 或内部令牌）"
                : "测试 Agent 鉴权已关闭";
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Map.of("code", 401, "message", message));
        } catch (Exception ex) {
            body = ("{\"code\":401,\"message\":\"unauthorized\"}").getBytes(StandardCharsets.UTF_8);
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(body);
    }
}
