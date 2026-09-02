package com.ai.mall.gateway.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GatewayExceptionHandler unit tests.
 * Uses mocked reactive components to verify error response serialization.
 */
@ExtendWith(MockitoExtension.class)
class GatewayExceptionHandlerTest {

    private GatewayExceptionHandler handler;

    @Mock
    private ServerWebExchange exchange;
    @Mock
    private ServerHttpRequest request;
    @Mock
    private ServerHttpResponse response;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new GatewayExceptionHandler();
        lenient().when(exchange.getRequest()).thenReturn(request);
        lenient().when(exchange.getResponse()).thenReturn(response);
        lenient().when(request.getURI()).thenReturn(URI.create("/api/test"));
        lenient().when(response.isCommitted()).thenReturn(false);
    }

    @Test
    void testHandleNotFoundException() {
        when(response.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        when(response.bufferFactory()).thenReturn(new DefaultDataBufferFactory());
        when(response.writeWith(any(org.reactivestreams.Publisher.class))).thenReturn(Mono.empty());

        NotFoundException ex = new NotFoundException("Service not found");
        Mono<Void> result = handler.handle(exchange, ex);

        StepVerifier.create(result).verifyComplete();

        // Verify response status and content type
        verify(response).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        assertEquals("application/json",
                response.getHeaders().getContentType().toString());
    }

    @Test
    void testHandleResponseStatusException() {
        when(response.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        when(response.bufferFactory()).thenReturn(new DefaultDataBufferFactory());
        when(response.writeWith(any(org.reactivestreams.Publisher.class))).thenReturn(Mono.empty());

        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数错误");
        Mono<Void> result = handler.handle(exchange, ex);

        StepVerifier.create(result).verifyComplete();
        verify(response).setStatusCode(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testHandleGenericException() {
        when(response.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        when(response.bufferFactory()).thenReturn(new DefaultDataBufferFactory());
        when(response.writeWith(any(org.reactivestreams.Publisher.class))).thenReturn(Mono.empty());

        RuntimeException ex = new RuntimeException("数据库连接失败");
        Mono<Void> result = handler.handle(exchange, ex);

        StepVerifier.create(result).verifyComplete();
        verify(response).setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void testHandleCommittedResponse() {
        when(response.isCommitted()).thenReturn(true);

        RuntimeException ex = new RuntimeException("已提交的响应");
        Mono<Void> result = handler.handle(exchange, ex);

        StepVerifier.create(result).expectError().verify();
    }

    @Test
    void testResponseBody_NotFoundException() throws Exception {
        when(response.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        when(response.bufferFactory()).thenReturn(new DefaultDataBufferFactory());

        // Capture the DataBuffer written to response
        when(response.writeWith(any(org.reactivestreams.Publisher.class))).thenAnswer(invocation -> {
            org.reactivestreams.Publisher<? extends DataBuffer> pub = invocation.getArgument(0);
            DataBuffer buffer = Mono.from(pub).block();
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            String body = new String(bytes, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(body, Map.class);
            assertEquals(503, result.get("code"));
            assertEquals("服务未找到", result.get("message"));
            return Mono.empty();
        });

        NotFoundException ex = new NotFoundException("Not found");
        handler.handle(exchange, ex).block();
    }

    @Test
    void testResponseBody_GenericException() throws Exception {
        when(response.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        when(response.bufferFactory()).thenReturn(new DefaultDataBufferFactory());

        when(response.writeWith(any(org.reactivestreams.Publisher.class))).thenAnswer(invocation -> {
            org.reactivestreams.Publisher<? extends DataBuffer> pub = invocation.getArgument(0);
            DataBuffer buffer = Mono.from(pub).block();
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            String body = new String(bytes, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(body, Map.class);
            assertEquals(500, result.get("code"));
            assertEquals("服务器内部错误", result.get("message"));
            return Mono.empty();
        });

        handler.handle(exchange, new RuntimeException("error")).block();
    }
}