package com.ai.mall.agent.ops.service.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    @Test
    void testPublishToSubscribedTopic() {
        AtomicInteger counter = new AtomicInteger(0);

        eventBus.subscribe("test.topic", event -> counter.incrementAndGet());
        eventBus.publish("test.topic", "hello");

        assertEquals(1, counter.get());
    }

    @Test
    void testPublishToMultipleSubscribers() {
        AtomicInteger counter1 = new AtomicInteger(0);
        AtomicInteger counter2 = new AtomicInteger(0);

        eventBus.subscribe("test.topic", event -> counter1.incrementAndGet());
        eventBus.subscribe("test.topic", event -> counter2.incrementAndGet());
        eventBus.publish("test.topic", "hello");

        assertEquals(1, counter1.get());
        assertEquals(1, counter2.get());
    }

    @Test
    void testPublishToUnsubscribedTopicDoesNothing() {
        eventBus.subscribe("topic.a", event -> { throw new RuntimeException("Should not be called"); });
        // Publishing to a different topic should not trigger the subscriber
        eventBus.publish("topic.b", "data");
        // If no exception is thrown, the test passes
    }

    @Test
    void testSubscriberReceivesCorrectEvent() {
        AtomicInteger counter = new AtomicInteger(0);
        String[] receivedEvent = new String[1];

        eventBus.subscribe("test.topic", event -> {
            counter.incrementAndGet();
            receivedEvent[0] = (String) event;
        });

        eventBus.publish("test.topic", "expected-data");

        assertEquals(1, counter.get());
        assertEquals("expected-data", receivedEvent[0]);
    }

    @Test
    void testSubscriberErrorDoesNotPropagate() {
        eventBus.subscribe("test.topic", event -> { throw new RuntimeException("Subscriber error"); });
        // This should not throw
        assertDoesNotThrow(() -> eventBus.publish("test.topic", "data"));
    }

    @Test
    void testMultipleTopicsAreIndependent() {
        AtomicInteger topicACounter = new AtomicInteger(0);
        AtomicInteger topicBCounter = new AtomicInteger(0);

        eventBus.subscribe("topic.a", event -> topicACounter.incrementAndGet());
        eventBus.subscribe("topic.b", event -> topicBCounter.incrementAndGet());

        eventBus.publish("topic.a", "data");
        assertEquals(1, topicACounter.get());
        assertEquals(0, topicBCounter.get());

        eventBus.publish("topic.b", "data");
        assertEquals(1, topicACounter.get());
        assertEquals(1, topicBCounter.get());
    }

    @Test
    void testConcurrentEventHandling() throws InterruptedException {
        int threadCount = 10;
        int eventsPerThread = 20;
        AtomicInteger totalReceived = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        eventBus.subscribe("concurrent.topic", event -> {
            totalReceived.incrementAndGet();
        });

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < eventsPerThread; j++) {
                        eventBus.publish("concurrent.topic", "event-" + threadId + "-" + j);
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Concurrent events should complete within timeout");
        assertEquals(threadCount * eventsPerThread, totalReceived.get());
    }

    @Test
    void testConcurrentSubscribeAndPublish() throws InterruptedException {
        AtomicInteger received = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        // Thread 1: subscribe
        new Thread(() -> {
            eventBus.subscribe("dynamic.topic", event -> received.incrementAndGet());
            latch.countDown();
        }).start();

        // Thread 2: publish
        new Thread(() -> {
            eventBus.publish("dynamic.topic", "data");
            latch.countDown();
        }).start();

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Concurrent subscribe and publish should complete");
    }

    @Test
    void testSameSubscriberReceivesMultiplePublishes() {
        ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();

        eventBus.subscribe("test.topic", event -> received.add((String) event));

        eventBus.publish("test.topic", "first");
        eventBus.publish("test.topic", "second");
        eventBus.publish("test.topic", "third");

        assertEquals(3, received.size());
        assertTrue(received.contains("first"));
        assertTrue(received.contains("second"));
        assertTrue(received.contains("third"));
    }

    @Test
    void testOneFailingSubscriberDoesNotAffectOthers() {
        AtomicInteger successCounter = new AtomicInteger(0);

        eventBus.subscribe("test.topic", event -> { throw new RuntimeException("Failing subscriber"); });
        eventBus.subscribe("test.topic", event -> successCounter.incrementAndGet());

        assertDoesNotThrow(() -> eventBus.publish("test.topic", "data"));
        assertEquals(1, successCounter.get());
    }
}