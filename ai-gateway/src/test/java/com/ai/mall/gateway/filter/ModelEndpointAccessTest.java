package com.ai.mall.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ModelEndpointAccessTest {
    @Test void anonymousModelListAndBoundedProbeReachTheirControllers() {
        var filter=new JwtAuthenticationFilter();
        for(String path:new String[]{"/agent/customer/api/v1/models","/agent/customer/api/v1/models/test"}) {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
            var forwarded=new AtomicBoolean();
            filter.filter(exchange,next -> { forwarded.set(true); return Mono.empty(); }).block();
            assertTrue(forwarded.get());
        }
    }
    @Test void modelSubpathsAndKnowledgeWritesRemainProtected() {
        var filter=new JwtAuthenticationFilter();
        for(String path:new String[]{"/agent/customer/api/v1/models/pull","/agent/customer/api/v1/knowledge/ingest","/order/list"}) {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
            filter.filter(exchange,next -> { fail("Protected path was forwarded"); return Mono.empty(); }).block();
            assertEquals(HttpStatus.UNAUTHORIZED,exchange.getResponse().getStatusCode());
        }
    }
}
