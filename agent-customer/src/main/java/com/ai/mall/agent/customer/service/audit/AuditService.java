package com.ai.mall.agent.customer.service.audit;

import com.ai.mall.agent.customer.model.AuditEvent;
import com.ai.mall.agent.customer.service.security.SensitiveDataMasker;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 审计留痕服务
 * <p>
 * 记录一次会话中的关键节点（提问、工具调用、护栏拦截、最终回答、用户反馈），
 * 用于：人工校对、幻觉率与拦截率统计、越权行为追溯。
 * <p>
 * 设计原则：
 * 1. 旁路弱依赖：审计写入失败只记日志，绝不影响正常对话（审计挂了不能让客服不可用）。
 * 2. 内容脱敏：任何进入审计流水的内容都先过脱敏器，敏感信息不落库。
 * 3. 有界存储：单会话最多保留 {@value #MAX_EVENTS_PER_SESSION} 条，TTL 7 天。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SensitiveDataMasker sensitiveDataMasker;

    private static final String AUDIT_KEY_PREFIX = "agent:audit:";
    private static final int MAX_EVENTS_PER_SESSION = 200;
    private static final long AUDIT_TTL_DAYS = 7;
    private static final int MAX_DETAIL_LENGTH = 500;

    /**
     * 独立 ObjectMapper：审计数据需要稳定的时间格式，不跟随业务 ObjectMapper 的配置变化
     */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * 记录一条审计事件
     *
     * @param event 审计事件（detail 会被自动脱敏与截断）
     */
    public void record(AuditEvent event) {
        if (event == null || event.getSessionId() == null) {
            log.warn("审计事件缺少会话ID，已丢弃");
            return;
        }

        try {
            if (event.getId() == null || event.getId().isBlank()) {
                event.setId(UUID.randomUUID().toString());
            }
            // 入库前脱敏，确保 PII 不落库
            event.setDetail(sensitiveDataMasker.snippet(event.getDetail(), MAX_DETAIL_LENGTH));

            String key = AUDIT_KEY_PREFIX + event.getSessionId();
            stringRedisTemplate.opsForList().rightPush(key, AUDIT_MAPPER.writeValueAsString(event));
            // 只保留最近 N 条，避免单会话无限增长
            stringRedisTemplate.opsForList().trim(key, -MAX_EVENTS_PER_SESSION, -1);
            stringRedisTemplate.expire(key, AUDIT_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            // 审计是旁路能力，失败不能阻断主流程
            log.error("写入审计事件失败, sessionId={}, type={}, err={}",
                    event.getSessionId(), event.getType(), e.getMessage());
        }
    }

    /**
     * 查询某会话的审计流水，供人工校对使用
     *
     * @param sessionId 会话ID
     * @param limit     返回条数上限
     * @return 审计事件列表（按时间正序）
     */
    public List<AuditEvent> listEvents(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }

        int size = Math.max(1, Math.min(limit, MAX_EVENTS_PER_SESSION));
        try {
            List<String> rawList = stringRedisTemplate.opsForList()
                    .range(AUDIT_KEY_PREFIX + sessionId, 0, size - 1);
            if (rawList == null || rawList.isEmpty()) {
                return Collections.emptyList();
            }

            List<AuditEvent> events = new ArrayList<>(rawList.size());
            for (String raw : rawList) {
                try {
                    events.add(AUDIT_MAPPER.readValue(raw, AuditEvent.class));
                } catch (Exception e) {
                    log.warn("审计事件反序列化失败，已跳过: {}", e.getMessage());
                }
            }
            return events;
        } catch (Exception e) {
            log.error("读取审计流水失败, sessionId={}, err={}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
