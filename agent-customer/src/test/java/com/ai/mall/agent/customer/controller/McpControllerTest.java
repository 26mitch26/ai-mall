package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.service.mcp.McpProtocolService;
import com.ai.mall.agent.customer.service.security.MemberIdentityResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(McpController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "agent.mcp.allowed-origins=https://allowed.example")
class McpControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private McpProtocolService protocolService;
    @MockitoBean private MemberIdentityResolver memberIdentityResolver;

    @BeforeEach
    void setUp() {
        when(memberIdentityResolver.resolve(anyString(), nullable(String.class))).thenAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            String authorization = invocation.getArgument(1);
            if (authorization == null || authorization.isBlank()) return ToolInvocationContext.anonymous(sessionId);
            if ("Bearer valid-member-token".equals(authorization)) {
                return ToolInvocationContext.builder().sessionId(sessionId).memberId("73")
                        .userToken("valid-member-token").build();
            }
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        });
        when(protocolService.handle(any(JsonNode.class), any(ToolInvocationContext.class))).thenAnswer(invocation -> {
            JsonNode request = invocation.getArgument(0);
            return objectMapper.createObjectNode().put("jsonrpc", "2.0")
                    .<com.fasterxml.jackson.databind.node.ObjectNode>set("id", request.path("id").deepCopy())
                    .set("result", objectMapper.createObjectNode());
        });
    }

    @Test
    void acceptsJsonInitializeAndAllowsOnlyConfiguredOrigin() throws Exception {
        mockMvc.perform(validPost("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test-client","version":"1"}}}
                        """).header(HttpHeaders.ORIGIN, "https://allowed.example"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1));
        verify(memberIdentityResolver).resolve(anyString(), isNull());
    }

    @Test
    void rejectsWrongContentTypeMissingAcceptAndUnlistedOrigin() throws Exception {
        mockMvc.perform(post("/mcp").contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotAcceptable());
        mockMvc.perform(validPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
                        .header(HttpHeaders.ORIGIN, "https://attacker.example"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(protocolService, memberIdentityResolver);
    }

    @Test
    void requiresNegotiatedProtocolHeaderForSubsequentRequests() throws Exception {
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32600));
        mockMvc.perform(validPost("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}")
                        .header("MCP-Protocol-Version", "2025-11-25"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32600));
        verifyNoInteractions(protocolService);
    }

    @Test
    void invalidMemberTokenFailsClosedBeforeProtocolDispatch() throws Exception {
        mockMvc.perform(validPost("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                        .header("MCP-Protocol-Version", "2025-06-18"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(protocolService);
    }

    @Test
    void requestBodyMemberIdsNeverBecomeTheResolvedIdentity() throws Exception {
        mockMvc.perform(validPost("""
                        {"jsonrpc":"2.0","id":4,"method":"tools/list","memberId":"999","userId":"999","params":{"memberId":"999"}}
                        """).header("MCP-Protocol-Version", "2025-06-18"))
                .andExpect(status().isOk());

        ArgumentCaptor<ToolInvocationContext> context = ArgumentCaptor.forClass(ToolInvocationContext.class);
        verify(protocolService).handle(any(JsonNode.class), context.capture());
        assertFalse(context.getValue().isAuthenticated());
        assertNull(context.getValue().getMemberId());
        assertNull(context.getValue().getUserToken());
        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        verify(memberIdentityResolver).resolve(sessionId.capture(), isNull());
        assertTrue(sessionId.getValue().matches("[A-Za-z0-9_-]{1,128}"));
    }

    @Test
    void getHasNoEventStreamAndReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/mcp").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isMethodNotAllowed());
    }

    private MockHttpServletRequestBuilder validPost(String body) {
        return post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
    }
}
