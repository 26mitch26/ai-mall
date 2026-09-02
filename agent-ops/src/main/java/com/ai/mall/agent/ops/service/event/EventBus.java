package com.ai.mall.agent.ops.service.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
public class EventBus {

    public static final String AIOPS_ALERTS   = "aiops.alerts";
    public static final String AIOPS_EVENTS   = "aiops.events";
    public static final String AIOPS_COMMANDS  = "aiops.commands";
    public static final String AIOPS_AUDIT    = "aiops.audit";

    private static final Map<EventType, String> EVENT_TYPE_TOPIC_MAP = Map.of(
            EventType.ALERT,   AIOPS_ALERTS,
            EventType.EVENT,   AIOPS_EVENTS,
            EventType.COMMAND, AIOPS_COMMANDS,
            EventType.AUDIT,   AIOPS_AUDIT
    );

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();

    public EventBus(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据事件类型路由到对应Kafka Topic发送消息
     */
    public void publish(EventType eventType, Object event) {
        String topic = EVENT_TYPE_TOPIC_MAP.get(eventType);
        if (topic == null) {
            log.error("Unknown event type: {}, falling back to AIOPS_EVENTS", eventType);
            topic = AIOPS_EVENTS;
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, payload);
            log.info("Published event to Kafka topic [{}]: {}", topic, payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for topic [{}]: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * 兼容旧接口：直接指定Topic发送
     */
    public void publish(String topic, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, payload);
            log.info("Published event to Kafka topic [{}]: {}", topic, payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for topic [{}]: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * 本地订阅注册（用于Kafka监听器回调分发）
     */
    public void subscribe(String topic, Consumer<Object> handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.info("Subscribed to topic: {}", topic);
    }

    private void dispatchToLocalSubscribers(String topic, String payload) {
        List<Consumer<Object>> topicSubscribers = subscribers.get(topic);
        if (topicSubscribers != null) {
            for (Consumer<Object> subscriber : topicSubscribers) {
                try {
                    subscriber.accept(payload);
                } catch (Exception e) {
                    log.error("Error dispatching event to local subscriber on topic [{}]: {}", topic, e.getMessage(), e);
                }
            }
        }
    }

    // ==================== Kafka Listener ====================

    @KafkaListener(topics = AIOPS_ALERTS, groupId = "aiops-alerts-consumer")
    public void onAlert(String payload) {
        log.info("Received alert event from [{}]: {}", AIOPS_ALERTS, payload);
        dispatchToLocalSubscribers(AIOPS_ALERTS, payload);
    }

    @KafkaListener(topics = AIOPS_EVENTS, groupId = "aiops-events-consumer")
    public void onEvent(String payload) {
        log.info("Received event from [{}]: {}", AIOPS_EVENTS, payload);
        dispatchToLocalSubscribers(AIOPS_EVENTS, payload);
    }

    @KafkaListener(topics = AIOPS_COMMANDS, groupId = "aiops-commands-consumer")
    public void onCommand(String payload) {
        log.info("Received command from [{}]: {}", AIOPS_COMMANDS, payload);
        dispatchToLocalSubscribers(AIOPS_COMMANDS, payload);
    }

    @KafkaListener(topics = AIOPS_AUDIT, groupId = "aiops-audit-consumer")
    public void onAudit(String payload) {
        log.info("Received audit event from [{}]: {}", AIOPS_AUDIT, payload);
        dispatchToLocalSubscribers(AIOPS_AUDIT, payload);
    }

    /**
     * 事件类型枚举，用于路由到不同Topic
     */
    public enum EventType {
        ALERT, EVENT, COMMAND, AUDIT
    }
}
