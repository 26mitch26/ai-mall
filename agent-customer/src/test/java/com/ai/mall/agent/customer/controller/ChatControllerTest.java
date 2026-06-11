package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.model.ChatRequest;
import com.ai.mall.agent.customer.model.ChatResponse;
import com.ai.mall.agent.customer.service.agent.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatService chatService;

    @Test
    void testChatEndpointReturnsProperResponse() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setSessionId("session-1");
        request.setMessage("你好");
        request.setUserId("user-1");

        ChatResponse response = ChatResponse.builder()
                .sessionId("session-1")
                .message("你好")
                .answer("你好！有什么可以帮助您的吗？")
                .intent("general")
                .responseTime(150L)
                .build();

        when(chatService.chat(any(ChatRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.answer").value("你好！有什么可以帮助您的吗？"))
                .andExpect(jsonPath("$.intent").value("general"))
                .andExpect(jsonPath("$.responseTime").value(150));
    }

    @Test
    void testChatEndpointWithNewSession() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("查询订单");

        ChatResponse response = ChatResponse.builder()
                .sessionId("new-session-id")
                .message("查询订单")
                .answer("请提供您的订单号")
                .intent("order_query")
                .responseTime(200L)
                .build();

        when(chatService.chat(any(ChatRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("new-session-id"))
                .andExpect(jsonPath("$.answer").value("请提供您的订单号"));
    }

    @Test
    void testClearSessionEndpoint() throws Exception {
        doNothing().when(chatService).clearSession("session-1");

        mockMvc.perform(delete("/api/v1/chat/session/session-1"))
                .andExpect(status().isOk());

        verify(chatService).clearSession("session-1");
    }

    @Test
    void testChatEndpointHandles400BadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("invalid json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testChatEndpointHandlesServiceError() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("test");

        when(chatService.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("Service unavailable"));

        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void testStreamEndpointReturnsSseEmitter() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setSessionId("session-1");
        request.setMessage("你好");

        ChatResponse response = ChatResponse.builder()
                .sessionId("session-1")
                .message("你好")
                .answer("你好！")
                .intent("general")
                .responseTime(100L)
                .build();

        when(chatService.chat(any(ChatRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }
}