package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.ChatRequest;
import com.ai.mall.agent.customer.model.ChatResponse;
import com.ai.mall.agent.customer.service.memory.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ReActAgent reactAgent;
    private final MemoryService memoryService;

    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        log.info("Processing chat request for session: {}", sessionId);

        String answer = reactAgent.think(sessionId, request.getMessage());

        long responseTime = System.currentTimeMillis() - startTime;

        return ChatResponse.builder()
                .sessionId(sessionId)
                .message(request.getMessage())
                .answer(answer)
                .intent("general")
                .responseTime(responseTime)
                .build();
    }

    public void clearSession(String sessionId) {
        memoryService.clearSession(sessionId);
        log.info("Cleared session: {}", sessionId);
    }
}
