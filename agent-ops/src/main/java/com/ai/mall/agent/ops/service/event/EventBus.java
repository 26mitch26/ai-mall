package com.ai.mall.agent.ops.service.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
public class EventBus {

    private final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();

    public void publish(String topic, Object event) {
        log.info("Publishing event to topic {}: {}", topic, event);
        List<Consumer<Object>> topicSubscribers = subscribers.get(topic);
        if (topicSubscribers != null) {
            for (Consumer<Object> subscriber : topicSubscribers) {
                try {
                    subscriber.accept(event);
                } catch (Exception e) {
                    log.error("Error processing event: {}", e.getMessage());
                }
            }
        }
    }

    public void subscribe(String topic, Consumer<Object> handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.info("Subscribed to topic: {}", topic);
    }
}
