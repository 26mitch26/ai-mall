package com.ai.mall.agent.ops.service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * EventBus 单元测试
 * <p>
 * 语义说明：EventBus 的发布链路为「publish → Kafka → @KafkaListener 回调 →
 * dispatchToLocalSubscribers 本地分发」。单测不依赖真实 Kafka：
 * - 发布侧：验证消息被发送到正确的 Kafka topic
 * - 订阅侧：通过调用对应 topic 的 listener 方法模拟「Kafka 收到消息后的回调分发」
 */
@ExtendWith(MockitoExtension.class)
class EventBusTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus(kafkaTemplate, new ObjectMapper());
    }

    @Test
    void testPublishSendsEventToKafkaTopic() {
        eventBus.publish("test.topic", "hello");

        verify(kafkaTemplate).send(eq("test.topic"), eq("\"hello\""));
    }

    @Test
    void testPublishByEventTypeRoutesToMappedTopic() {
        eventBus.publish(EventBus.EventType.ALERT, "{\"level\":\"high\"}");

        verify(kafkaTemplate).send(eq(EventBus.AIOPS_ALERTS), anyString());
    }

    @Test
    void testListenerDispatchToLocalSubscribers() {
        AtomicInteger counter = new AtomicInteger(0);
        String[] received = new String[1];

        eventBus.subscribe(EventBus.AIOPS_ALERTS, event -> {
            counter.incrementAndGet();
            received[0] = (String) event;
        });

        // 模拟 Kafka 收到 alerts topic 的消息后触发回调
        eventBus.onAlert("alert-payload");

        assertEquals(1, counter.get());
        assertEquals("alert-payload", received[0]);
    }

    @Test
    void testListenerDispatchIsFilteredByTopic() {
        AtomicInteger counter = new AtomicInteger(0);

        // 只订阅 EVENTS topic
        eventBus.subscribe(EventBus.AIOPS_EVENTS, event -> counter.incrementAndGet());

        // alerts topic 的消息不应触发 events 的订阅者
        eventBus.onAlert("alert-payload");
        assertEquals(0, counter.get());

        eventBus.onEvent("event-payload");
        assertEquals(1, counter.get());
    }

    @Test
    void testOneFailingSubscriberDoesNotAffectOthers() {
        AtomicInteger successCounter = new AtomicInteger(0);

        eventBus.subscribe(EventBus.AIOPS_ALERTS, event -> { throw new RuntimeException("Failing subscriber"); });
        eventBus.subscribe(EventBus.AIOPS_ALERTS, event -> successCounter.incrementAndGet());

        // 单个订阅者抛异常不应影响其他订阅者，也不应向上传播
        assertDoesNotThrow(() -> eventBus.onAlert("data"));
        assertEquals(1, successCounter.get());
    }

    @Test
    void testSameSubscriberReceivesMultipleEvents() {
        AtomicInteger counter = new AtomicInteger(0);

        eventBus.subscribe(EventBus.AIOPS_COMMANDS, event -> counter.incrementAndGet());

        eventBus.onCommand("cmd-1");
        eventBus.onCommand("cmd-2");
        eventBus.onCommand("cmd-3");

        assertEquals(3, counter.get());
    }

    @Test
    void testUnknownEventTypeFallsBackToDefaultTopic() {
        // 枚举外不存在的类型不会编译，这里只验证 publish(String) 不经过类型路由
        eventBus.publish("custom.topic", "payload");

        verify(kafkaTemplate).send(eq("custom.topic"), anyString());
        // 自定义 topic 不应误发到预定义 Kafka topic
        verify(kafkaTemplate, never()).send(eq(EventBus.AIOPS_ALERTS), anyString());
    }
}
