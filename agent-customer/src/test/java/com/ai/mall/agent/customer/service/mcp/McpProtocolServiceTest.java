package com.ai.mall.agent.customer.service.mcp;

import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.model.workflow.AfterSaleWorkflowState;
import com.ai.mall.agent.customer.service.audit.AuditService;
import com.ai.mall.agent.customer.service.tool.ToolRegistry;
import com.ai.mall.agent.customer.service.workflow.AfterSaleWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpProtocolServiceTest {

    @Mock private ToolRegistry toolRegistry;
    @Mock private AuditService auditService;
    @Mock private AfterSaleWorkflowService afterSaleWorkflowService;

    private ObjectMapper mapper;
    private McpProtocolService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        service = new McpProtocolService(mapper, toolRegistry, auditService, afterSaleWorkflowService);
    }

    @Test
    void initializeNegotiatesOnlySupportedProtocolVersion() throws Exception {
        var accepted = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                """), ToolInvocationContext.anonymous("s"));
        assertEquals("2025-06-18", accepted.path("result").path("protocolVersion").asText());
        var negotiated=service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"new-client","version":"1"}}}
                """),ToolInvocationContext.anonymous("s"));
        assertEquals("2025-06-18",negotiated.path("result").path("protocolVersion").asText());

        var rejected = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                """), ToolInvocationContext.anonymous("s"));
        assertEquals(-32602, rejected.path("error").path("code").asInt());
    }

    @Test
    void handlesPingNotificationsAndMethodErrors() throws Exception {
        var ping = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":"p","method":"ping"}
                """), null);
        assertTrue(ping.path("result").isObject());
        var notification = service.handle(mapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"), null);
        assertNull(notification);
        var unknown = service.handle(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"bogus\"}"), null);
        assertEquals(-32601, unknown.path("error").path("code").asInt());
    }

    @Test
    void toolsListScopesPrivateToolsToAuthenticatedContext() throws Exception {
        var request = mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        var anonymous = service.handle(request, ToolInvocationContext.anonymous("anon"));
        assertEquals(1, anonymous.path("result").path("tools").size());
        assertEquals("search_products", anonymous.path("result").path("tools").get(0).path("name").asText());

        var member = ToolInvocationContext.builder().sessionId("member-session").memberId("42").userToken("verified").build();
        var authenticated = service.handle(request, member);
        assertEquals(3, authenticated.path("result").path("tools").size());
    }

    @Test
    void blocksPrivateToolsAndRejectsExtraOrInvalidArguments() throws Exception {
        var context = ToolInvocationContext.anonymous("anon");
        var privateCall = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_order_info","arguments":{"order_sn":"A123"}}}
                """), context);
        assertTrue(privateCall.path("result").path("isError").asBoolean());
        verifyNoInteractions(toolRegistry);

        var malformed = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_products","arguments":{"keyword":"phone","userId":"999"}}}
                """), context);
        assertEquals(-32602, malformed.path("error").path("code").asInt());

        var badPage = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_products","arguments":{"keyword":"phone","page":0}}}
                """), context);
        assertEquals(-32602, badPage.path("error").path("code").asInt());
        verifyNoInteractions(toolRegistry);
    }

    @Test
    void callsOnlyWhitelistedPublicToolAndAuditsIt() throws Exception {
        var context = ToolInvocationContext.anonymous("public-session");
        when(toolRegistry.executeStructuredTool(eq("search_products"), anyString(), eq(context)))
                .thenReturn("{\"items\":[]}");
        var response = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"search_products","arguments":{"keyword":"phone","page":1}}}
                """), context);
        assertFalse(response.path("result").path("isError").asBoolean());
        assertEquals("{\"items\":[]}", response.path("result").path("content").get(0).path("text").asText());
        verify(toolRegistry).executeStructuredTool(eq("search_products"), anyString(), eq(context));
        verify(auditService, times(2)).record(any());
        verifyNoInteractions(afterSaleWorkflowService);
    }

    @Test
    void preparesAfterSaleDraftWithoutInvokingLegacyWriteTool() throws Exception {
        var context = ToolInvocationContext.builder().sessionId("member-session").memberId("42")
                .userToken("verified").build();
        var state = AfterSaleWorkflowState.builder().taskId("draft-1").status("WAITING_CONFIRMATION")
                .orderSn("ORDER-1").reason("damaged").draft("review this")
                .orderSummary("order summary").version(1).build();
        when(afterSaleWorkflowService.prepare(context, "ORDER-1", "damaged", "box broken")).thenReturn(state);

        var response = service.handle(mapper.readTree("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"prepare_after_sale","arguments":{"order_sn":"ORDER-1","reason":"damaged","description":"box broken"}}}
                """), context);

        assertFalse(response.path("result").path("isError").asBoolean());
        var text = response.path("result").path("content").get(0).path("text").asText();
        assertTrue(text.contains("WAITING_CONFIRMATION"));
        assertTrue(text.contains("draft-1"));
        verify(afterSaleWorkflowService).prepare(context, "ORDER-1", "damaged", "box broken");
        verifyNoInteractions(toolRegistry);
    }
}
