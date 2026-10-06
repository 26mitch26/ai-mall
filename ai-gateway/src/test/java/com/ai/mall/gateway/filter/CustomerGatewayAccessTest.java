package com.ai.mall.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerGatewayAccessTest {
    @Test void anonymousPublicReadsAndMcpReachToolLevelAuthorization() {
        var filter=new JwtAuthenticationFilter();
        var chain=mock(GatewayFilterChain.class);when(chain.filter(any())).thenReturn(Mono.empty());
        for(String path:new String[]{"/agent/customer/api/v1/chat","/agent/customer/api/v1/evaluation/retrieve","/agent/customer/mcp"}) {
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post(path)),chain).block();
        }
        verify(chain,times(3)).filter(any());
    }
    @Test void anonymousIngestAndPrivateWorkflowRemainDeniedAtGateway() {
        var filter=new JwtAuthenticationFilter();var chain=mock(GatewayFilterChain.class);
        for(String path:new String[]{"/agent/customer/api/v1/knowledge/ingest","/agent/customer/api/v1/workflows/after-sale"}) {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post(path));filter.filter(exchange,chain).block();
            assertEquals(401,exchange.getResponse().getStatusCode().value());
        }
        verifyNoInteractions(chain);
    }
}
