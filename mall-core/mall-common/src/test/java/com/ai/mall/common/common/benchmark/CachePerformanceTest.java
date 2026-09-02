package com.ai.mall.common.benchmark;

import com.ai.mall.common.service.RedisService;
import com.ai.mall.common.service.impl.RedisServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存性能单元测试
 * 使用Testcontainers启动Redis，验证Redis缓存对QPS的提升效果
 * 断言QPS提升 >= 3倍（300%）
 */
@Testcontainers
class CachePerformanceTest {

    private static final String REDIS_IMAGE = "redis:7-alpine";

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    private CacheBenchmarkService benchmarkService;
    private RedisService redisService;

    /** 商品缓存key前缀（与CacheBenchmarkService保持一致） */
    private static final String PRODUCT_CACHE_PREFIX = "benchmark:product:";
    private static final String CHAT_MEMORY_CACHE_PREFIX = "benchmark:chat_memory:";
    private static final long CACHE_EXPIRE_SECONDS = 3600;

    /** 模拟MySQL查询的延迟范围(ms) */
    private static final long DB_MIN_DELAY_MS = 5;
    private static final long DB_MAX_DELAY_MS = 15;

    /** 模拟对话记忆数据库查询的延迟范围(ms) */
    private static final long CHAT_DB_MIN_DELAY_MS = 8;
    private static final long CHAT_DB_MAX_DELAY_MS = 20;

    /** 压测参数 */
    private static final int TOTAL_REQUESTS = 5000;
    private static final int CONCURRENCY = 50;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // 创建真实的RedisTemplate连接Testcontainers中的Redis
        String host = redisContainer.getHost();
        Integer port = redisContainer.getMappedPort(6379);

        org.springframework.data.redis.connection.RedisStandaloneConfiguration config =
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(host, port);

        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory connectionFactory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new org.springframework.data.redis.serializer.StringRedisSerializer());
        redisTemplate.setValueSerializer(new org.springframework.data.redis.serializer.JdkSerializationRedisSerializer());
        redisTemplate.afterPropertiesSet();

        // 构建RedisService
        RedisServiceImpl redisServiceImpl = new RedisServiceImpl();
        try {
            var field = RedisServiceImpl.class.getDeclaredField("redisTemplate");
            field.setAccessible(true);
            field.set(redisServiceImpl, redisTemplate);
        } catch (Exception e) {
            throw new RuntimeException("注入RedisTemplate失败", e);
        }
        redisService = redisServiceImpl;

        // 构建CacheBenchmarkService
        benchmarkService = new CacheBenchmarkService();
        try {
            var field = CacheBenchmarkService.class.getDeclaredField("redisService");
            field.setAccessible(true);
            field.set(benchmarkService, redisService);
        } catch (Exception e) {
            throw new RuntimeException("注入RedisService失败", e);
        }
    }

    /**
     * 商品查询压测：对比无缓存 vs Redis缓存
     * 断言QPS提升 >= 3倍
     */
    @Test
    void testProductQueryWithCacheVsNoCache(TestInfo testInfo) {
        System.out.println("\n====== " + testInfo.getDisplayName() + " ======");

        List<Long> productIds = List.of(1001L, 1002L, 1003L, 1004L, 1005L,
                1006L, 1007L, 1008L, 1009L, 1010L);

        // 清除缓存
        for (Long pid : productIds) {
            redisService.del(PRODUCT_CACHE_PREFIX + pid);
        }

        // 模拟MySQL商品查询
        AtomicLong dbQueryCount = new AtomicLong(0);
        Supplier<Object> noCacheQuery = () -> {
            try {
                long delay = DB_MIN_DELAY_MS + ThreadLocalRandom.current().nextLong(DB_MAX_DELAY_MS - DB_MIN_DELAY_MS);
                Thread.sleep(delay);
                dbQueryCount.incrementAndGet();
                return Map.of("productId", 1001L, "name", "iPhone 16 Pro", "price", 8999.00);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        };

        Map<String, Object> report = benchmarkService.benchmarkProductQuery(
                TOTAL_REQUESTS, CONCURRENCY, noCacheQuery, productIds);

        // 验证报告完整性
        assertNotNull(report.get("noCacheResult"), "无缓存结果不应为空");
        assertNotNull(report.get("cacheResult"), "有缓存结果不应为空");
        assertNotNull(report.get("improvementRatio"), "提升比例不应为空");

        // 解析提升比例
        String improvementRatioStr = (String) report.get("improvementRatio");
        double improvementRatio = Double.parseDouble(improvementRatioStr.replace("x", ""));

        System.out.println("无缓存QPS: " + report.get("qpsNoCache"));
        System.out.println("有缓存QPS: " + report.get("qpsWithCache"));
        System.out.println("提升比例: " + improvementRatioStr);
        System.out.println("提升百分比: " + report.get("improvementPercent"));
        System.out.println("无缓存平均延迟: " + report.get("avgLatencyNoCache"));
        System.out.println("有缓存平均延迟: " + report.get("avgLatencyWithCache"));
        System.out.println("无缓存P99延迟: " + report.get("p99LatencyNoCache"));
        System.out.println("有缓存P99延迟: " + report.get("p99LatencyWithCache"));

        // 核心断言：QPS提升 >= 3倍（300%）
        assertTrue(improvementRatio >= 3.0,
                String.format("QPS提升应 >= 3倍，实际为 %.2fx (提升%.2f%%)", improvementRatio, (improvementRatio - 1) * 100));

        System.out.println("✓ 断言通过: QPS提升 " + improvementRatioStr + " >= 3.00x");
    }

    /**
     * 对话记忆压测：对比无缓存 vs Redis缓存
     * 断言QPS提升 >= 3倍
     */
    @Test
    void testChatMemoryWithCacheVsNoCache(TestInfo testInfo) {
        System.out.println("\n====== " + testInfo.getDisplayName() + " ======");

        List<String> sessionIds = List.of("sess_001", "sess_002", "sess_003", "sess_004", "sess_005",
                "sess_006", "sess_007", "sess_008", "sess_009", "sess_010");

        // 清除缓存
        for (String sid : sessionIds) {
            redisService.del(CHAT_MEMORY_CACHE_PREFIX + sid);
        }

        // 模拟数据库对话记忆查询
        AtomicLong dbQueryCount = new AtomicLong(0);
        Supplier<Object> noCacheQuery = () -> {
            try {
                long delay = CHAT_DB_MIN_DELAY_MS + ThreadLocalRandom.current().nextLong(CHAT_DB_MAX_DELAY_MS - CHAT_DB_MIN_DELAY_MS);
                Thread.sleep(delay);
                dbQueryCount.incrementAndGet();
                return Map.of("sessionId", "sess_001", "messages", List.of(
                        Map.of("role", "user", "content", "推荐一款手机"),
                        Map.of("role", "assistant", "content", "为您推荐iPhone 16 Pro")
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        };

        Map<String, Object> report = benchmarkService.benchmarkChatMemory(
                TOTAL_REQUESTS, CONCURRENCY, noCacheQuery, sessionIds);

        // 验证报告完整性
        assertNotNull(report.get("noCacheResult"), "无缓存结果不应为空");
        assertNotNull(report.get("cacheResult"), "有缓存结果不应为空");
        assertNotNull(report.get("improvementRatio"), "提升比例不应为空");

        // 解析提升比例
        String improvementRatioStr = (String) report.get("improvementRatio");
        double improvementRatio = Double.parseDouble(improvementRatioStr.replace("x", ""));

        System.out.println("无缓存QPS: " + report.get("qpsNoCache"));
        System.out.println("有缓存QPS: " + report.get("qpsWithCache"));
        System.out.println("提升比例: " + improvementRatioStr);
        System.out.println("提升百分比: " + report.get("improvementPercent"));
        System.out.println("无缓存平均延迟: " + report.get("avgLatencyNoCache"));
        System.out.println("有缓存平均延迟: " + report.get("avgLatencyWithCache"));
        System.out.println("无缓存P99延迟: " + report.get("p99LatencyNoCache"));
        System.out.println("有缓存P99延迟: " + report.get("p99LatencyWithCache"));

        // 核心断言：QPS提升 >= 3倍（300%）
        assertTrue(improvementRatio >= 3.0,
                String.format("QPS提升应 >= 3倍，实际为 %.2fx (提升%.2f%%)", improvementRatio, (improvementRatio - 1) * 100));

        System.out.println("✓ 断言通过: QPS提升 " + improvementRatioStr + " >= 3.00x");
    }
}
