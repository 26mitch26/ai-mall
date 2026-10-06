package com.ai.mall.agent.customer.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AgentLlmResourceSettingsTest {
    @Test void oversizedPromptDoesNotSendHttpOrConsumeModelCallBudget() {
        AgentLlmClient client = new AgentLlmClient(new ObjectMapper(), "http://localhost:11434/api/chat", "test-model", 64, .2);
        ReflectionTestUtils.setField(client, "maxPromptCharacters", 100);
        MockRestServiceServer server = MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(client,"restTemplate")).build();
        try (var trace = com.ai.mall.agent.customer.service.telemetry.AgentTelemetry.open(1)) {
            assertThrows(IllegalArgumentException.class, () -> client.chat("x".repeat(101)));
            assertEquals(0, trace.summary().modelCalls());
            assertTrue(com.ai.mall.agent.customer.service.telemetry.AgentTelemetry.reserveModelCall());
        }
        server.verify();
    }
    @Test void lowMemorySettingsReachTheActualOllamaRequest() {
        AgentLlmClient client = new AgentLlmClient(new ObjectMapper(), "http://localhost:11434/api/chat", "test-model", 64, .2);
        ReflectionTestUtils.setField(client, "contextTokens", 2048);
        ReflectionTestUtils.setField(client, "gpuLayers", 0);
        ReflectionTestUtils.setField(client, "keepAlive", "0");
        ReflectionTestUtils.setField(client, "releaseEmbeddingBeforeGeneration", true);
        MockRestServiceServer server = MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(client,"restTemplate")).build();
        server.expect(requestTo("http://localhost:11434/api/generate"))
                .andExpect(jsonPath("$.model").value("bge-m3"))
                .andExpect(jsonPath("$.keep_alive").value(0))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(jsonPath("$.think").value(false))
                .andExpect(jsonPath("$.options.num_ctx").value(2048))
                .andExpect(jsonPath("$.options.num_gpu").value(0))
                .andExpect(jsonPath("$.keep_alive").value("0"))
                .andRespond(withSuccess("{\"message\":{\"content\":\"answer\"},\"prompt_eval_count\":10,\"eval_count\":3}",MediaType.APPLICATION_JSON));
        assertEquals("answer", client.chat("question"));
        server.verify();
    }
}
