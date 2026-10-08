package com.ai.mall.common.service.impl;

import com.ai.mall.common.service.RedisService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * 并发限购验证：直接对落地后的 {@link RedisServiceImpl#luaCheckAndIncrPurchaseLimit} 做并发压测，
 * 用真实 Redis 执行 Lua 原子脚本，证明"同一用户多端同时下单只通过一单"。
 *
 * 核心要证明的：原纯 DB 限购存在竞态（两请求都查到"还差 1 件"后都通过）；
 * 现在的 Redis Lua 把"读额度 + 判断 + 预占"原子化，多端并发下只有一个人能抢到配额。
 *
 * 环境自适应：优先用 Testcontainers 起 Docker Redis；若本机无 Docker，则回退到本地已运行的
 * localhost:6379（开发机通常已起）；两者皆无则跳过（不阻断构建）。
 * 使用唯一 key 前缀，避免清空/污染本地 Redis 既有数据。
 */
class PurchaseLimitConcurrencyTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    // Docker 不可用时，回退到本地已运行的 Redis
    private static boolean useLocalRedis = false;

    private RedisTemplate<String, Object> template;
    private RedisService redisService;
    private String prefix;

    @BeforeAll
    static void startRedis() {
        try {
            REDIS.start();
            return;
        } catch (Throwable ignored) {
            // Docker 不可用，尝试本地 Redis
        }
        if (isLocalRedisUp()) {
            useLocalRedis = true;
            return;
        }
        abort("无可用 Redis（Docker 不可用且 localhost:6379 未监听），跳过集成并发测试");
    }

    @AfterAll
    static void stopRedis() {
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @BeforeEach
    void setUp() {
        String host = useLocalRedis ? "localhost" : REDIS.getHost();
        int port = useLocalRedis ? 6379 : REDIS.getMappedPort(6379);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();

        // 关键：value 用"字符串化"序列化器，保证 Lua 里 tonumber(get) 能解析数字，
        // 且脚本入参（Long 数量/上限）也能被序列化成 "1" 这样的可读字符串。
        RedisSerializer<Object> stringy = new RedisSerializer<>() {
            @Override
            public byte[] serialize(Object o) {
                return o == null ? null : String.valueOf(o).getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Object deserialize(byte[] bytes) {
                return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
            }
        };

        template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setDefaultSerializer(stringy);
        template.setKeySerializer(stringy);
        template.setValueSerializer(stringy);
        template.setHashKeySerializer(stringy);
        template.setHashValueSerializer(stringy);
        template.afterPropertiesSet();

        RedisServiceImpl impl = new RedisServiceImpl();
        try {
            var field = RedisServiceImpl.class.getDeclaredField("redisTemplate");
            field.setAccessible(true);
            field.set(impl, template);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        redisService = impl;

        // 每用例使用唯一前缀，避免干扰本地 Redis 既有数据，也避免多次运行串扰
        prefix = "pl-test-" + UUID.randomUUID() + "-";
    }

    private String key(String suffix) {
        return prefix + suffix;
    }

    /**
     * 场景一：一人一单（perLimit=1）。同一用户 8 个线程同时抢购，最终只允许 1 单通过。
     */
    @Test
    void concurrentSameUserOnlyOneAllowedWhenPerLimitOne() throws Exception {
        String k = key("100:42");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return redisService.luaCheckAndIncrPurchaseLimit(k, 1, 1, 60);
            }));
        }
        start.countDown();

        int passed = 0;
        for (Future<Boolean> f : futures) {
            if (Boolean.TRUE.equals(f.get(10, TimeUnit.SECONDS))) {
                passed++;
            }
        }
        pool.shutdown();

        assertEquals(1, passed, "并发下只应有一个请求通过限购（一人一单）");
        assertEquals("1", String.valueOf(template.opsForValue().get(k)),
                "Redis 中该用户已购计数应为 1");
    }

    /**
     * 场景二：限购 N 件（perLimit=3）。3 个并发各买 1 件都应通过，第 4 个被拒。
     */
    @Test
    void concurrentUpToLimitThenReject() throws Exception {
        String k = key("200:7");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return redisService.luaCheckAndIncrPurchaseLimit(k, 1, 3, 60);
            }));
        }
        start.countDown();

        int passed = 0;
        for (Future<Boolean> f : futures) {
            if (Boolean.TRUE.equals(f.get(10, TimeUnit.SECONDS))) {
                passed++;
            }
        }
        pool.shutdown();

        assertEquals(3, passed, "前 3 个并发请求应通过，第 4 个应被拒");
        assertEquals("3", String.valueOf(template.opsForValue().get(k)));
    }

    /**
     * 场景三：取消/超时回滚后额度恢复。对应生产里 releasePurchaseLimit 的逻辑。
     */
    @Test
    void releaseRestoresQuota() {
        String k = key("300:9");
        assertTrue(redisService.luaCheckAndIncrPurchaseLimit(k, 1, 1, 60),
                "首次购买应通过");
        assertFalse(redisService.luaCheckAndIncrPurchaseLimit(k, 1, 1, 60),
                "已达上限应被拒");

        redisService.decr(k, 1); // 取消订单回滚预占

        assertTrue(redisService.luaCheckAndIncrPurchaseLimit(k, 1, 1, 60),
                "回滚后额度恢复，可再次购买");
    }

    private static boolean isLocalRedisUp() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 6379), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
