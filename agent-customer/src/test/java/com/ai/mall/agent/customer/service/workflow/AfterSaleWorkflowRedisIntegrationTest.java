package com.ai.mall.agent.customer.service.workflow;

import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.service.rag.RagService;
import com.ai.mall.agent.customer.service.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Redis/CAS/restart/concurrency; portal and model boundaries are explicitly simulated. */
@Tag("integration")
class AfterSaleWorkflowRedisIntegrationTest {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private ToolRegistry tools;
    private RagService rag;
    private ObjectMapper mapper;
    private AfterSaleWorkflowService service;
    private final ToolInvocationContext member=ToolInvocationContext.builder().sessionId("verify-session").memberId("7").userToken("test-token-never-persist").build();

    @BeforeAll static void redisConnection() {
        int port=Integer.getInteger("agent.verify.redis-port",-1);
        Assumptions.assumeTrue(port>0,"Pass the port of a dedicated empty verification Redis");
        connection=new LettuceConnectionFactory("127.0.0.1",port);connection.afterPropertiesSet();connection.start();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
        assertEquals("PONG",connection.getConnection().ping());
    }
    @AfterAll static void closeRedis() { if(connection!=null) connection.destroy(); }
    @BeforeEach void dependencies() {
        tools=mock(ToolRegistry.class);rag=mock(RagService.class);mapper=new ObjectMapper().findAndRegisterModules();
        when(tools.executeStructuredTool(eq("get_order_info"),anyString(),any())).thenReturn("{\"code\":200,\"data\":{\"orderSn\":\"SN-VERIFY\",\"memberId\":7,\"status\":3}}");
        when(rag.retrieveWithEvidence(anyString(),anyInt())).thenReturn(new RagService.RetrievalOutcome(List.of(),0,0,0,true));
        service=new AfterSaleWorkflowService(tools,rag,redis,mapper);
    }
    @Test void restartRestoresFrozenDraftAndRepeatingConfirmationExecutesOnce() {
        when(tools.executeStructuredTool(eq("create_after_sale"),anyString(),any())).thenReturn("{\"code\":200,\"data\":{\"id\":72}}");
        var draft=service.prepare(member,"SN-VERIFY","外观破损","待核实");
        String persisted=redis.opsForValue().get("agent:workflow:after-sale:"+draft.getTaskId());
        assertNotNull(persisted);assertFalse(persisted.contains(member.getUserToken()));
        var restarted=new AfterSaleWorkflowService(tools,rag,redis,mapper);
        assertEquals(draft.getDraft(),restarted.get(member,draft.getTaskId()).getDraft());
        var done=restarted.confirm(member,draft.getTaskId(),draft.getVersion(),true);
        assertEquals("COMPLETED",done.getStatus());
        assertEquals("COMPLETED",restarted.confirm(member,draft.getTaskId(),draft.getVersion(),true).getStatus());
        verify(tools,times(1)).executeStructuredTool(eq("create_after_sale"),anyString(),argThat(c -> c.isWriteApproved()&&c.getOperationId().equals(draft.getOperationId())));
        var other=ToolInvocationContext.builder().sessionId("verify-session").memberId("8").userToken("other").build();
        assertThrows(SecurityException.class,()->restarted.get(other,draft.getTaskId()));
    }
    @Test void rejectAndStaleVersionDoNotExecuteWrite() {
        var draft=service.prepare(member,"SN-VERIFY","外观破损","待核实");
        assertThrows(IllegalStateException.class,()->service.confirm(member,draft.getTaskId(),99,true));
        assertEquals("REJECTED",service.confirm(member,draft.getTaskId(),draft.getVersion(),false).getStatus());
        verify(tools,never()).executeStructuredTool(eq("create_after_sale"),anyString(),any());
    }
    @Test void concurrentConfirmationsHaveOnlyOneWriter() throws Exception {
        AtomicInteger writes=new AtomicInteger();
        when(tools.executeStructuredTool(eq("create_after_sale"),anyString(),any())).thenAnswer(call -> {
            writes.incrementAndGet();Thread.sleep(100);return "{\"code\":200,\"data\":{\"id\":73}}";
        });
        var draft=service.prepare(member,"SN-VERIFY","外观破损","待核实");
        var pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        Callable<String> confirm=()->{start.await();try{return service.confirm(member,draft.getTaskId(),draft.getVersion(),true).getStatus();}catch(IllegalStateException conflict){return "CONFLICT";}};
        try {var first=pool.submit(confirm);var second=pool.submit(confirm);start.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);}
        finally{pool.shutdownNow();}
        assertEquals(1,writes.get());assertEquals("COMPLETED",service.get(member,draft.getTaskId()).getStatus());
    }
    @Test void lostReplyIsReconciledWithoutRepeatingWrite() {
        when(tools.executeStructuredTool(eq("create_after_sale"),anyString(),any())).thenReturn("{\"error\":\"timeout after commit\"}");
        when(tools.lookupOperation(eq("after_sale"),anyString(),any())).thenReturn("{\"code\":200,\"data\":{\"status\":\"COMPLETED\",\"result\":{\"id\":74}}}");
        var draft=service.prepare(member,"SN-VERIFY","外观破损","待核实");
        var done=service.confirm(member,draft.getTaskId(),draft.getVersion(),true);
        assertEquals("UNKNOWN",done.getStatus());
        var recovered=new AfterSaleWorkflowService(tools,rag,redis,mapper).get(member,draft.getTaskId());
        assertEquals("COMPLETED",recovered.getStatus());
        assertTrue(recovered.getResult().contains("74"));
        verify(tools,times(1)).executeStructuredTool(eq("create_after_sale"),anyString(),any());
    }
}
