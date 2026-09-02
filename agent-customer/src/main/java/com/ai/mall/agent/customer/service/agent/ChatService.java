package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.AuditEvent;
import com.ai.mall.agent.customer.model.AuditEventType;
import com.ai.mall.agent.customer.model.ChatRequest;
import com.ai.mall.agent.customer.model.ChatResponse;
import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.service.audit.AuditService;
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
    private final AuditService auditService;

    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        log.info("Processing chat request for session: {}", sessionId);

        // 携带用户身份进入工具调用链路，使敏感工具能做鉴权与数据隔离
        ToolInvocationContext context = ToolInvocationContext.builder()
                .sessionId(sessionId)
                .memberId(request.getUserId())
                .userToken(request.getUserToken())
                .build();

        auditService.record(AuditEvent.builder()
                .sessionId(sessionId)
                .memberId(request.getUserId())
                .type(AuditEventType.USER_QUERY.getCode())
                .detail("用户提问: " + request.getMessage())
                .build());

        String answer = reactAgent.think(sessionId, request.getMessage(), context);

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
