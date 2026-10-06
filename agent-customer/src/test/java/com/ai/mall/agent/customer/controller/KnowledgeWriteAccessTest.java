package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.service.rag.RagService;
import com.ai.mall.agent.customer.service.rag.SemanticAnswerCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeWriteAccessTest {
    @Test void anonymousAndWrongCredentialsCannotChangePolicy() throws Exception {
        RagService rag = mock(RagService.class);
        KnowledgeController controller = new KnowledgeController(rag, mock(SemanticAnswerCacheService.class));
        ReflectionTestUtils.setField(controller, "knowledgeAdminToken", "test-only-admin");
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/knowledge/ingest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"tampered policy\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/knowledge/ingest").header("X-Knowledge-Admin-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"tampered policy\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(rag);
    }

    @Test void unconfiguredAdminCredentialFailsClosed() throws Exception {
        RagService rag = mock(RagService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(rag, mock(SemanticAnswerCacheService.class))).build();
        mvc.perform(post("/api/v1/knowledge/ingest").header("X-Knowledge-Admin-Token", "arbitrary")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"tampered policy\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(rag);
    }

    @Test void authorizedKnowledgePublisherCanIndexPolicy() throws Exception {
        RagService rag = mock(RagService.class);
        KnowledgeController controller = new KnowledgeController(rag, mock(SemanticAnswerCacheService.class));
        ReflectionTestUtils.setField(controller, "knowledgeAdminToken", "test-only-admin");
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/knowledge/ingest").header("X-Knowledge-Admin-Token", "test-only-admin")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"reviewed policy\",\"source\":\"policy.md\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.indexedChunks").value(1));
        verify(rag).indexDocuments(anyList(), isNull());
    }
}
