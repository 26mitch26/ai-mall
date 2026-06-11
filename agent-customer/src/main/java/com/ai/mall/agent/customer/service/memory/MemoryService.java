package com.ai.mall.agent.customer.service.memory;

import com.ai.mall.agent.customer.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String SESSION_PREFIX = "chat:session:";
    private static final String MEMORY_PREFIX = "chat:memory:";
    private static final int MAX_SHORT_TERM_MESSAGES = 10;
    private static final int SHORT_TERM_EXPIRE_HOURS = 24;

    public void addMessage(String sessionId, ChatMessage message) {
        String key = SESSION_PREFIX + sessionId;

        List<ChatMessage> messages = getShortTermMemory(sessionId);
        messages.add(message);

        if (messages.size() > MAX_SHORT_TERM_MESSAGES) {
            messages = messages.subList(messages.size() - MAX_SHORT_TERM_MESSAGES, messages.size());
        }

        redisTemplate.opsForValue().set(key, messages, SHORT_TERM_EXPIRE_HOURS, TimeUnit.HOURS);
        log.debug("Added message to session {}: {}", sessionId, message.getContent());
    }

    public List<ChatMessage> getShortTermMemory(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        Object data = redisTemplate.opsForValue().get(key);
        if (data == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.convertValue(data,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ChatMessage.class));
        } catch (Exception e) {
            log.error("Error deserializing memory: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public void clearSession(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        redisTemplate.delete(key);
        log.info("Cleared session: {}", sessionId);
    }

    public ChatMessage createMessage(String sessionId, String role, String content) {
        return ChatMessage.builder()
                .id(java.util.UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
