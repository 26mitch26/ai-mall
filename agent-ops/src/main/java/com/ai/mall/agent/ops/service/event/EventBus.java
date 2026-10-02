package com.ai.mall.agent.ops.service.event;

import com.ai.mall.agent.ops.model.AlertEvent;
import com.ai.mall.agent.ops.model.ChangeDecision;
import com.ai.mall.agent.ops.model.HealAction;
import com.ai.mall.agent.ops.model.RCAResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 智能运维事件总线：Agent 之间唯一的通信通道。
 *
 * <h3>设计意图</h3>
 * 各 Agent 之间<b>不直接方法调用</b>，而是「上游产出事件 → 事件总线 → 下游消费」，
 * 使 Monitor / RCA / Heal / Change 四个环节可以独立演进、独立伸缩，
 * 也让整条处置链路可审计、可回放（每一环的事件都落在对应的 Topic 上）。
 *
 * <h3>链路与 Topic</h3>
 * <pre>
 *   MonitorAgent --(AlertEvent)----> aiops.alerts  --> RCAAgent
 *   RCAAgent     --(RCAResult)-----> aiops.events  --> HealAgent
 *   HealAgent    --(HealAction)----> aiops.commands-> ChangeAgent
 *   ChangeAgent  --(ChangeDecision)-> aiops.audit
 * </pre>
 *
 * <h3>两种工作模式</h3>
 * <ul>
 *   <li><b>Kafka 模式（默认，生产）</b>：事件发布到 Kafka，由 {@code @KafkaListener}
 *       消费并分发给本地订阅者。真正的跨实例解耦与异步削峰。</li>
 *   <li><b>本地直连模式（{@code aiops.eventbus.local-mode=true}）</b>：跳过 Kafka，
 *       在发布线程内同步分发。用于单元测试与单机演示，避免异步带来的时序不确定性，
 *       使集成测试无需依赖真实 Kafka 容器。</li>
 * </ul>
 */
@Slf4j
@Service
public class EventBus {

    public static final String AIOPS_ALERTS = "aiops.alerts";
    public static final String AIOPS_EVENTS = "aiops.events";
    public static final String AIOPS_COMMANDS = "aiops.commands";
    public static final String AIOPS_AUDIT = "aiops.audit";

    private static final Map<EventType, String> EVENT_TYPE_TOPIC_MAP = Map.of(
            EventType.ALERT, AIOPS_ALERTS,
            EventType.EVENT, AIOPS_EVENTS,
            EventType.COMMAND, AIOPS_COMMANDS,
            EventType.AUDIT, AIOPS_AUDIT
    );

    /**
     * 各 Topic 对应的载荷类型。
     * 消费端据此把 JSON 还原成领域对象，订阅者拿到的是强类型事件而非裸字符串。
     */
    private static final Map<String, Class<?>> TOPIC_PAYLOAD_TYPES = Map.of(
            AIOPS_ALERTS, AlertEvent.class,
            AIOPS_EVENTS, RCAResult.class,
            AIOPS_COMMANDS, HealAction.class,
            AIOPS_AUDIT, ChangeDecision.class
    );

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();

    /**
     * 本地直连模式开关：为 true 时跳过 Kafka，同步分发给本地订阅者。
     */
    @Value("${aiops.eventbus.local-mode:false}")
    private boolean localMode;

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
        publish(topic, event);
    }

    /**
     * 向指定 Topic 发布事件。
     *
     * <p>Kafka 模式下只投递到 Kafka，由 {@code @KafkaListener} 异步消费后分发；
     * 本地直连模式下直接在当前线程分发给本地订阅者。
     */
    public void publish(String topic, Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for topic [{}]: {}", topic, e.getMessage(), e);
            return;
        }

        if (localMode) {
            log.debug("[local-mode] Dispatching event to local subscribers of [{}]", topic);
            dispatchToLocalSubscribers(topic, payload);
            return;
        }

        kafkaTemplate.send(topic, payload);
        log.info("Published event to Kafka topic [{}]", topic);
    }

    /**
     * 类型安全订阅：订阅者直接收到反序列化后的领域对象。
     *
     * @param topic       订阅的 Topic
     * @param payloadType 该 Topic 的载荷类型，需与 {@link #TOPIC_PAYLOAD_TYPES} 一致
     * @param handler     事件处理器
     */
    public <T> void subscribe(String topic, Class<T> payloadType, Consumer<T> handler) {
        Objects.requireNonNull(payloadType, "payloadType must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                .add(event -> handler.accept(payloadType.cast(event)));
        log.info("Subscribed to topic [{}] expecting payload [{}]",
                topic, payloadType.getSimpleName());
    }

    /**
     * 兼容旧接口：不指定载荷类型，直接消费反序列化后的对象。
     * 若反序列化失败则退化为原始 JSON 字符串。
     */
    public void subscribe(String topic, Consumer<Object> handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.info("Subscribed to topic: {}", topic);
    }

    /**
     * 分发给本地订阅者：先按 Topic 对应的类型把 JSON 还原成领域对象。
     */
    private void dispatchToLocalSubscribers(String topic, String payload) {
        List<Consumer<Object>> topicSubscribers = subscribers.get(topic);
        if (topicSubscribers == null || topicSubscribers.isEmpty()) {
            log.debug("No local subscriber for topic [{}], message dropped", topic);
            return;
        }

        Object event = deserialize(topic, payload);
        for (Consumer<Object> subscriber : topicSubscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.error("Error dispatching event to local subscriber on topic [{}]: {}",
                        topic, e.getMessage(), e);
            }
        }
    }

    /**
     * 反序列化：成功返回领域对象；失败时退化为原始字符串，
     * 以免单条脏消息导致整条链路中断。
     */
    private Object deserialize(String topic, String payload) {
        Class<?> type = TOPIC_PAYLOAD_TYPES.get(topic);
        if (type == null) {
            return payload;
        }
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            log.warn("Cannot deserialize payload on topic [{}] as [{}], "
                            + "falling back to raw string: {}",
                    topic, type.getSimpleName(), e.getMessage());
            return payload;
        }
    }

    // ==================== Kafka Listener ====================

    @KafkaListener(topics = AIOPS_ALERTS, groupId = "aiops-alerts-consumer")
    public void onAlert(String payload) {
        log.info("Received alert event from [{}]", AIOPS_ALERTS);
        dispatchToLocalSubscribers(AIOPS_ALERTS, payload);
    }

    @KafkaListener(topics = AIOPS_EVENTS, groupId = "aiops-events-consumer")
    public void onEvent(String payload) {
        log.info("Received event from [{}]", AIOPS_EVENTS);
        dispatchToLocalSubscribers(AIOPS_EVENTS, payload);
    }

    @KafkaListener(topics = AIOPS_COMMANDS, groupId = "aiops-commands-consumer")
    public void onCommand(String payload) {
        log.info("Received command from [{}]", AIOPS_COMMANDS);
        dispatchToLocalSubscribers(AIOPS_COMMANDS, payload);
    }

    @KafkaListener(topics = AIOPS_AUDIT, groupId = "aiops-audit-consumer")
    public void onAudit(String payload) {
        log.info("Received audit event from [{}]", AIOPS_AUDIT);
        dispatchToLocalSubscribers(AIOPS_AUDIT, payload);
    }

    /**
     * 事件类型枚举，用于路由到不同Topic
     */
    public enum EventType {
        ALERT, EVENT, COMMAND, AUDIT
    }
}
