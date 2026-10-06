package com.ai.mall.agent.customer.service.llm;

import com.ai.mall.agent.customer.model.ChatModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ModelSelectionTest {
    private ChatModelConfig local(String name) { return ChatModelConfig.builder().provider("ollama").model(name).thinkingSupported(false).build(); }
    private AgentLlmClient client() { return new AgentLlmClient(new ObjectMapper(),"http://localhost:11434/api/chat","default",64,.2); }
    private MockRestServiceServer server(AgentLlmClient client) {
        return MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(client,"restTemplate")).build();
    }
    private InetAddress publicAddress() {
        try { return InetAddress.getByAddress(new byte[]{8,8,8,8}); } catch(Exception e) { throw new RuntimeException(e); }
    }
    @Test void overridesReachActualOllamaPayloadAndDefaultIsRestored() {
        var client=client(); var server=server(client);
        for(String model:List.of("first","second","default")) server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(jsonPath("$.model").value(model))
                .andRespond(withSuccess("{\"model\":\""+model+"\",\"message\":{\"content\":\"ok\"}}",MediaType.APPLICATION_JSON));
        for(String model:List.of("first","second")) try(var scope=RequestModelContext.open(local(model),"default")) {
            assertEquals("ok",client.chat("test")); assertEquals(List.of(model),scope.usedModels());
        }
        assertEquals("ok",client.chat("test")); server.verify();
    }
    @Test void nonThinkingModelOmitsThinkAndProbeBoundsOutputAndResidency() {
        var client=client(); var server=server(client);
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(jsonPath("$.think").doesNotExist()).andExpect(jsonPath("$.keep_alive").value("0"))
                .andExpect(jsonPath("$.options.num_predict").value(32)).andExpect(jsonPath("$.options.num_ctx").value(1024))
                .andRespond(withSuccess("{\"message\":{\"content\":\"ok\"}}",MediaType.APPLICATION_JSON));
        try(var scope=RequestModelContext.open(local("vision-chat"),"default")) { scope.probeMode(); client.chat("test"); }
        server.verify();
    }
    @Test void modelContextsDoNotLeakAcrossConcurrentWorkersOrNestedScopes() {
        var a=CompletableFuture.supplyAsync(() -> { try(var scope=RequestModelContext.open(local("a"),"default")) { return RequestModelContext.modelOr("none"); } });
        var b=CompletableFuture.supplyAsync(() -> { try(var scope=RequestModelContext.open(local("b"),"default")) { return RequestModelContext.modelOr("none"); } });
        assertEquals("a",a.join()); assertEquals("b",b.join());
        assertEquals("none",RequestModelContext.modelOr("none"));
        try(var outer=RequestModelContext.open(local("outer"),"default")) {
            try(var inner=RequestModelContext.open(local("inner"),"default")) { assertEquals("inner",RequestModelContext.modelOr("none")); }
            assertEquals("outer",RequestModelContext.modelOr("none"));
        }
    }
    @Test void cacheNamespaceChangesWhenTheSelectedModelChanges() {
        String first;
        try(var scope=RequestModelContext.open(local("a"),"default")) { first=RequestModelContext.cacheNamespace(); }
        try(var scope=RequestModelContext.open(local("b"),"default")) { assertNotEquals(first,RequestModelContext.cacheNamespace()); }
    }
    @Test void cloudPayloadUsesBearerHeaderAndNoOllamaOptions() {
        var client=client(); var server=server(client);
        ReflectionTestUtils.setField(client,"cloudEndpoints",new CloudEndpointPolicy(host -> List.of(publicAddress())));
        var config=ChatModelConfig.builder().provider("openai-compatible").model("cloud-unit").baseUrl("https://example.com/v1").apiKey("unit-test-secret").build();
        server.expect(requestTo("https://example.com/v1/chat/completions"))
                .andExpect(header("Authorization","Bearer unit-test-secret"))
                .andExpect(jsonPath("$.model").value("cloud-unit")).andExpect(jsonPath("$.max_tokens").value(32))
                .andExpect(jsonPath("$.options").doesNotExist()).andExpect(jsonPath("$.think").doesNotExist())
                .andRespond(withSuccess("{\"model\":\"actual-cloud-unit\",\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",MediaType.APPLICATION_JSON));
        try(var scope=RequestModelContext.open(config,"default")) {
            scope.probeMode(); assertEquals("ok",client.chat("test")); assertEquals(List.of("actual-cloud-unit"),scope.usedModels());
        }
        server.verify(); assertFalse(config.toString().contains("unit-test-secret"));
    }
    @Test void cloudErrorsDoNotExposeProviderBodyOrCredentials() {
        var client=client(); var server=server(client);
        ReflectionTestUtils.setField(client,"cloudEndpoints",new CloudEndpointPolicy(host -> List.of(publicAddress())));
        var config=ChatModelConfig.builder().provider("openai-compatible").model("cloud-unit").baseUrl("https://example.com/v1").apiKey("unit-test-secret").build();
        server.expect(requestTo("https://example.com/v1/chat/completions"))
                .andRespond(withBadRequest().body("echo unit-test-secret"));
        try(var scope=RequestModelContext.open(config,"default")) {
            var error=assertThrows(IllegalStateException.class,() -> client.chat("test"));
            assertFalse(error.toString().contains("unit-test-secret")); assertNull(error.getCause());
        }
        server.verify();
    }
    @Test void oversizedCloudResponseIsRejectedWithASanitizedError() {
        var client=client(); var server=server(client);
        ReflectionTestUtils.setField(client,"cloudEndpoints",new CloudEndpointPolicy(host -> List.of(publicAddress())));
        var config=ChatModelConfig.builder().provider("openai-compatible").model("cloud-unit").baseUrl("https://example.com/v1").apiKey("unit-test-secret").build();
        server.expect(requestTo("https://example.com/v1/chat/completions"))
                .andRespond(withSuccess("x".repeat(1_048_577),MediaType.APPLICATION_JSON));
        try(var scope=RequestModelContext.open(config,"default")) {
            var error=assertThrows(IllegalStateException.class,() -> client.chat("test"));
            assertFalse(error.toString().contains("unit-test-secret"));
        }
        server.verify();
    }
    @Test void cloudEndpointShapeIsNormalizedAndUnsafeAddressesAreRejected() {
        var policy=new CloudEndpointPolicy(host -> List.of(publicAddress()));
        assertEquals("https://example.com/v1/chat/completions",policy.validate("https://example.com/v1/"));
        assertEquals("https://example.com/v1/chat/completions",policy.validate("https://example.com/v1/chat/completions"));
        for(String url:List.of("http://example.com/v1","https://key@example.com/v1","https://example.com/v1?key=secret"))
            assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> policy.validate(url));
        var internal=new CloudEndpointPolicy(host -> { try { return List.of(InetAddress.getByAddress(new byte[]{127,0,0,1})); } catch(Exception e) { throw new RuntimeException(e); } });
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> internal.validate("https://example.com/v1"));
    }
    @Test void memoryEstimateRejectsOversizedModelWithoutTreatingDiskSizeAsExactResidency() {
        long gb=1024L*1024*1024;
        assertTrue(LocalModelMemoryGuard.permits(gb/2,16*gb,4*gb));
        assertFalse(LocalModelMemoryGuard.permits(3*gb,16*gb,2*gb));
    }
}
