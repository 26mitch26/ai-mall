package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.service.rag.RagService;
import com.ai.mall.agent.customer.service.rag.SemanticAnswerCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeControllerStatusTest {
    @Test void lowMemoryStatusDoesNotAdvertiseDisabledVectorOrNeuralServices() {
        var controller = new KnowledgeController(mock(RagService.class), mock(SemanticAnswerCacheService.class));
        ReflectionTestUtils.setField(controller, "vectorStoreEnabled", false);
        ReflectionTestUtils.setField(controller, "llmBaseUrl", "http://127.0.0.1:1/api/chat");
        Map<String,Object> status = controller.status();
        assertEquals("未启用", status.get("vectorStore"));
        assertEquals("未启用", status.get("embeddingModel"));
        assertEquals("BM25", status.get("vectorIndex"));
        assertFalse(status.get("pipeline").toString().contains("neural"));
        assertFalse(status.get("pipeline").toString().contains("ANN"));
    }
}
