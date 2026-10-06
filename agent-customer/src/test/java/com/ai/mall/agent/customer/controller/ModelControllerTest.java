package com.ai.mall.agent.customer.controller;
import com.ai.mall.agent.customer.model.ChatModelConfig;
import com.ai.mall.agent.customer.service.llm.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelControllerTest {
    @Test void memoryGuardFailureIsReadableAndScopeIsClosed() {
        var catalog=mock(OllamaModelCatalog.class); var llm=mock(AgentLlmClient.class);
        var config=ChatModelConfig.builder().provider("ollama").model("test").build();
        when(catalog.validate(config)).thenReturn(config);
        when(llm.chat(anyString())).thenThrow(new IllegalStateException("当前可用内存不足，本次未加载模型"));
        var response=new ModelController(catalog,llm).test(config);
        assertEquals(503,response.getStatusCode().value());
        assertTrue(response.getBody().get("message").toString().contains("内存不足"));
        assertEquals("none",RequestModelContext.modelOr("none"));
    }
}
