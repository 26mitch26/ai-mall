package com.ai.mall.agent.test.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 把入口校验失败渲染成统一 JSON，与网关 {@code JwtAuthenticationFilter} 的响应体保持一致，
 * 便于前端 axios 拦截器与 MCP 客户端都能读到原因。
 */
@Slf4j
@RestControllerAdvice
public class TestApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? status.getReasonPhrase()
                : ex.getReason();
        if (status.is5xxServerError()) {
            log.error("Request failed with status {}: {}", status.value(), message);
        } else {
            log.debug("Request rejected with status {}: {}", status.value(), message);
        }
        return ResponseEntity.status(status).body(Map.of("code", status.value(), "message", message));
    }
}
