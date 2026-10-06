package com.ai.mall.agent.customer.service.memory;

import com.ai.mall.agent.customer.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Value("${ai.memory.archive-enabled:true}")
    private boolean archiveEnabled = true;
    /**
     * 长期记忆专用向量库（独立的 Milvus 集合）：与知识库向量库物理隔离，
     * 避免历史对话被知识检索命中、混进回答的"来源"里。
     * 字段名与 Bean 名一致，保证按类型注入时精确选中 memoryVectorStore。
     */
    private final VectorStore memoryVectorStore;

    private static final String SESSION_PREFIX = "chat:session:";
    private static final String MEMORY_PREFIX = "chat:memory:";
    private static final String LONG_TERM_METADATA_KEY = "sessionId";
    private static final int MAX_SHORT_TERM_MESSAGES = 10;
    private static final int SHORT_TERM_EXPIRE_HOURS = 24;
    private static final double IMPORTANCE_THRESHOLD = 0.6;

    // ==================== 短期记忆（Redis） ====================

    public void addMessage(String sessionId, ChatMessage message) {
        // 1. 存入Redis短期记忆（现有逻辑）
        String key = SESSION_PREFIX + sessionId;

        List<ChatMessage> messages = getShortTermMemory(sessionId);
        messages.add(message);

        if (messages.size() > MAX_SHORT_TERM_MESSAGES) {
            messages = messages.subList(messages.size() - MAX_SHORT_TERM_MESSAGES, messages.size());
        }

        redisTemplate.opsForValue().set(key, messages, SHORT_TERM_EXPIRE_HOURS, TimeUnit.HOURS);
        log.debug("Added message to session {}: {}", sessionId, message.getContent());

        // 2. 判断是否为重要消息，异步存入Milvus长期记忆
        if (archiveEnabled && isImportantMessage(message)) {
            CompletableFuture.runAsync(() -> {
                try {
                    storeLongTermMemory(sessionId, message);
                    log.info("Important message archived to long-term memory for session {}", sessionId);
                } catch (Exception e) {
                    log.error("Failed to store long-term memory: {}", e.getMessage());
                }
            });
        }
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

    // ==================== 长期记忆（Milvus） ====================

    /**
     * 将重要对话存入Milvus长期记忆
     */
    public void storeLongTermMemory(String sessionId, ChatMessage message) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(LONG_TERM_METADATA_KEY, sessionId);
        metadata.put("role", message.getRole());
        metadata.put("timestamp", message.getTimestamp() != null ? message.getTimestamp().toString() : LocalDateTime.now().toString());
        metadata.put("messageId", message.getId());

        Document document = new Document(message.getContent(), metadata);
        memoryVectorStore.add(List.of(document));
        log.debug("Stored long-term memory for session {}: {}", sessionId, message.getContent());
    }

    /**
     * 从Milvus检索相关的长期记忆
     */
    public List<Document> retrieveLongTermMemory(String sessionId, String query, int topK) {
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterBuilder.eq(LONG_TERM_METADATA_KEY, sessionId).build())
                .build();

        List<Document> results = memoryVectorStore.similaritySearch(searchRequest);
        log.debug("Retrieved {} long-term memories for session {} with query: {}", results.size(), sessionId, query);
        return results;
    }

    /**
     * 判断消息是否值得长期存储
     * 基于关键词和语义特征判断：用户偏好、关键决策、重要事实等
     */
    public boolean isImportantMessage(ChatMessage message) {
        if (message.getContent() == null || message.getContent().isBlank()) {
            return false;
        }

        String content = message.getContent().toLowerCase();

        // 用户偏好关键词
        String[] preferenceKeywords = {"喜欢", "偏好", "想要", "希望", "习惯", "prefer", "like", "want", "favorite"};
        // 关键决策关键词
        String[] decisionKeywords = {"决定", "选择", "确认", "购买", "下单", "decide", "choose", "confirm", "order"};
        // 重要事实关键词
        String[] factKeywords = {"地址", "电话", "账号", "会员", "过敏", "address", "phone", "account", "member", "allergy"};
        // 否定/投诉关键词
        String[] complaintKeywords = {"投诉", "不满", "退货", "退款", "complaint", "refund", "return"};

        double score = 0.0;
        score += matchKeywords(content, preferenceKeywords) * 0.3;
        score += matchKeywords(content, decisionKeywords) * 0.3;
        score += matchKeywords(content, factKeywords) * 0.25;
        score += matchKeywords(content, complaintKeywords) * 0.15;

        // 用户消息更容易包含重要信息
        if ("user".equals(message.getRole())) {
            score += 0.1;
        }

        return score >= IMPORTANCE_THRESHOLD;
    }

    /**
     * 将短期记忆中的重要内容沉淀到长期记忆
     */
    public void consolidateMemories(String sessionId) {
        List<ChatMessage> shortTermMessages = getShortTermMemory(sessionId);
        int consolidated = 0;

        for (ChatMessage message : shortTermMessages) {
            if (isImportantMessage(message)) {
                try {
                    storeLongTermMemory(sessionId, message);
                    consolidated++;
                } catch (Exception e) {
                    log.error("Failed to consolidate message {}: {}", message.getId(), e.getMessage());
                }
            }
        }

        log.info("Consolidated {} important memories from session {}", consolidated, sessionId);
    }

    // ==================== 综合记忆检索 ====================

    /**
     * 合并Redis短期记忆 + Milvus长期记忆检索结果
     */
    public Map<String, Object> getFullMemory(String sessionId, String currentQuery) {
        Map<String, Object> fullMemory = new HashMap<>();

        // 1. 获取Redis短期记忆
        List<ChatMessage> shortTermMemory = getShortTermMemory(sessionId);
        fullMemory.put("shortTermMemory", shortTermMemory);

        // 2. 从Milvus检索长期记忆
        List<Document> longTermMemory = retrieveLongTermMemory(sessionId, currentQuery, 5);
        List<Map<String, Object>> longTermSummary = longTermMemory.stream()
                .map(doc -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("content", doc.getText());
                    item.put("metadata", doc.getMetadata());
                    return item;
                })
                .collect(Collectors.toList());
        fullMemory.put("longTermMemory", longTermSummary);

        log.debug("Full memory for session {}: {} short-term, {} long-term",
                sessionId, shortTermMemory.size(), longTermMemory.size());
        return fullMemory;
    }

    // ==================== 记忆管理 ====================

    /**
     * 对短期记忆进行摘要后归档到长期记忆
     */
    public void summarizeAndArchive(String sessionId) {
        List<ChatMessage> shortTermMemory = getShortTermMemory(sessionId);

        if (shortTermMemory.isEmpty()) {
            log.info("No short-term memory to archive for session {}", sessionId);
            return;
        }

        // 将对话历史拼接为摘要文本存入长期记忆
        String conversationSummary = shortTermMemory.stream()
                .map(msg -> msg.getRole() + ": " + msg.getContent())
                .collect(Collectors.joining("\n"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(LONG_TERM_METADATA_KEY, sessionId);
        metadata.put("type", "summary");
        metadata.put("archivedAt", LocalDateTime.now().toString());
        metadata.put("messageCount", shortTermMemory.size());

        Document summaryDoc = new Document(
                "[会话摘要] " + conversationSummary,
                metadata
        );
        memoryVectorStore.add(List.of(summaryDoc));

        // 归档后清除短期记忆
        clearSession(sessionId);

        log.info("Summarized and archived {} messages for session {}", shortTermMemory.size(), sessionId);
    }

    /**
     * 获取记忆统计信息
     */
    public Map<String, Object> getMemoryStats(String sessionId) {
        Map<String, Object> stats = new HashMap<>();

        // 短期记忆统计
        List<ChatMessage> shortTermMemory = getShortTermMemory(sessionId);
        stats.put("shortTermCount", shortTermMemory.size());
        stats.put("shortTermMaxCapacity", MAX_SHORT_TERM_MESSAGES);
        stats.put("shortTermExpireHours", SHORT_TERM_EXPIRE_HOURS);

        long userMessages = shortTermMemory.stream()
                .filter(msg -> "user".equals(msg.getRole()))
                .count();
        long assistantMessages = shortTermMemory.stream()
                .filter(msg -> "assistant".equals(msg.getRole()))
                .count();
        stats.put("shortTermUserMessages", userMessages);
        stats.put("shortTermAssistantMessages", assistantMessages);

        // 长期记忆统计
        List<Document> longTermMemory = retrieveLongTermMemory(sessionId, "全部记忆概览", 100);
        stats.put("longTermCount", longTermMemory.size());

        long importantCount = shortTermMemory.stream()
                .filter(this::isImportantMessage)
                .count();
        stats.put("importantUnarchivedCount", importantCount);

        return stats;
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算内容与关键词的匹配度
     */
    private double matchKeywords(String content, String[] keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return 1.0;
            }
        }
        return 0.0;
    }
}
