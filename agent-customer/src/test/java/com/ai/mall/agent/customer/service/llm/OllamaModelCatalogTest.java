package com.ai.mall.agent.customer.service.llm;

import com.ai.mall.agent.customer.model.ChatModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OllamaModelCatalogTest {
    @Test void catalogReadsCapabilitiesWithoutLoadingOrPullingAndRejectsEmbeddings() {
        var catalog=new OllamaModelCatalog("http://localhost:11434/api/chat","chat:latest");
        var server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(catalog,"http")).build();
        server.expect(requestTo("http://localhost:11434/api/tags"))
                .andRespond(withSuccess("{\"models\":[{\"name\":\"chat:latest\"},{\"name\":\"bge:latest\"}]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:11434/api/show")).andExpect(jsonPath("$.model").value("chat:latest"))
                .andRespond(withSuccess("{\"capabilities\":[\"completion\",\"thinking\"]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:11434/api/show")).andExpect(jsonPath("$.model").value("bge:latest"))
                .andRespond(withSuccess("{\"capabilities\":[\"embedding\"]}",MediaType.APPLICATION_JSON));
        assertEquals(2,catalog.list(false).models().size());
        var selected=catalog.validate(ChatModelConfig.builder().provider("ollama").model("chat").build());
        assertEquals("chat:latest",selected.getModel()); assertTrue(selected.getThinkingSupported());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> catalog.validate(ChatModelConfig.builder().provider("ollama").model("bge:latest").build()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> catalog.validate(ChatModelConfig.builder().provider("ollama").model("missing").build()));
        assertNull(catalog.validate(null)); server.verify();
    }
}
