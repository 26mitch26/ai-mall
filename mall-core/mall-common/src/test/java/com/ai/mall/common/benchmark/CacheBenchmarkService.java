package com.ai.mall.common.benchmark;

import com.ai.mall.common.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 缓存性能基准测试引擎（仅测试使用）
 * 用于对比无缓存（直接查MySQL）与Redis缓存后的QPS差异，为"QPS提升300%"提供数据支撑
 *
 * <p>2026-09-05 压测改造：该压测引擎从生产代码（src/main）降级到 test 目录——
 * 原 {@code CacheBenchmarkRunner} 会在每次应用启动后自动执行 10000*2 次压测，
 * 造成与真实 k6 全链路压测争抢 CPU/Redis 资源，并显著放大虚拟线程下 JVM 崩溃风险。
 * 压测工具属工程验证手段，不应存在于运行时 classpath（面试视角即"demo 痕迹"）。
 * 有压测需求时通过 {@code CacheBenchmarkLiveTest} 显式触发即可。
 */
public class CacheBenchmarkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheBenchmarkService.class);

    /** 商品缓存key前缀 */
    private static final String PRODUCT_CACHE_PREFIX = "benchmark:product:";
    /** 对话记忆缓存key前缀 */
    private static final String CHAT_MEMORY_CACHE_PREFIX = "benchmark:chat_memory:";
    /** 缓存过期时间(秒) */
    private static final long CACHE_EXPIRE_SECONDS = 3600;

    /** 由测试反射注入真实实现 */
    private RedisService redisService;

    /**
     * 压测结果数据类
     */
    public static class BenchmarkResult {
        private String testName;
        private int totalRequests;
        private int concurrency;
        private long durationMs;
        private double qps;
        private double avgLatencyMs;
        private double p99LatencyMs;

        public BenchmarkResult() {}

        public BenchmarkResult(String testName, int totalRequests, int concurrency,
                               long durationMs, double qps, double avgLatencyMs, double p99LatencyMs) {
            this.testName = testName;
            this.totalRequests = totalRequests;
            this.concurrency = concurrency;
            this.durationMs = durationMs;
            this.qps = qps;
            this.avgLatencyMs = avgLatencyMs;
            this.p99LatencyMs = p99LatencyMs;
        }

        public String getTestName() { return testName; }
        public void setTestName(String testName) { this.testName = testName; }
        public int getTotalRequests() { return totalRequests; }
        public void setTotalRequests(int totalRequests) { this.totalRequests = totalRequests; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public double getQps() { return qps; }
        public void setQps(double qps) { this.qps = qps; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public void setAvgLatencyMs(double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
        public double getP99LatencyMs() { return p99LatencyMs; }
        public void setP99LatencyMs(double p99LatencyMs) { this.p99LatencyMs = p99LatencyMs; }

        @Override
        public String toString() {
            return String.format("[%s] 总请求=%d, 并发=%d, 耗时=%dms, QPS=%.2f, 平均延迟=%.2fms, P99延迟=%.2fms",
                    testName, totalRequests, concurrency, durationMs, qps, avgLatencyMs, p99LatencyMs);
        }
    }

    /**
     * 商品查询压测：对比无缓存 vs Redis缓存
     *
     * @param totalRequests 总请求数
     * @param concurrency   并发数
     * @param noCacheQuery  无缓存查询逻辑（模拟直接查MySQL）
     * @param productIds    要查询的商品ID列表
     * @return 无缓存和有缓存的压测结果对比
     */
    public Map<String, Object> benchmarkProductQuery(int totalRequests, int concurrency,
                                                      Supplier<Object> noCacheQuery, List<Long> productIds) {
        LOGGER.info("====== 商品查询压测开始 ======");
        LOGGER.info("总请求数: {}, 并发数: {}", totalRequests, concurrency);

        // 1. 清除缓存，确保无缓存基准测试不受影响
        clearProductCache(productIds);

        // 2. 预热
        warmUp(noCacheQuery, 100);

        // 3. 无缓存基准测试：直接查MySQL
        Supplier<Object> noCacheSupplier = () -> {
            try {
                return noCacheQuery.get();
            } catch (Exception e) {
                LOGGER.warn("无缓存查询异常: {}", e.getMessage());
                return null;
            }
        };
        BenchmarkResult noCacheResult = runBenchmark("商品查询-无缓存(MySQL直连)",
                totalRequests, concurrency, noCacheSupplier);

        // 4. 预热Redis缓存（将热点商品数据写入Redis）
        for (Long productId : productIds) {
            String cacheKey = PRODUCT_CACHE_PREFIX + productId;
            Object productData = noCacheQuery.get();
            if (productData != null) {
                redisService.set(cacheKey, productData, CACHE_EXPIRE_SECONDS);
            }
        }

        // 5. 有缓存测试：先查Redis，miss再查MySQL并回填
        Supplier<Object> cacheSupplier = () -> {
            try {
                // 模拟轮询不同商品ID
                Long pid = productIds.get(ThreadLocalRandom.current().nextInt(productIds.size()));
                String cacheKey = PRODUCT_CACHE_PREFIX + pid;
                Object cached = redisService.get(cacheKey);
                if (cached != null) {
                    return cached;
                }
                // Cache miss: 查MySQL并回填
                Object data = noCacheQuery.get();
                if (data != null) {
                    redisService.set(cacheKey, data, CACHE_EXPIRE_SECONDS);
                }
                return data;
            } catch (Exception e) {
                LOGGER.warn("缓存查询异常: {}", e.getMessage());
                return null;
            }
        };
        BenchmarkResult cacheResult = runBenchmark("商品查询-Redis缓存",
                totalRequests, concurrency, cacheSupplier);

        // 6. 计算提升比例
        double improvementRatio = noCacheResult.getQps() > 0
                ? cacheResult.getQps() / noCacheResult.getQps()
                : 0;
        double improvementPercent = (improvementRatio - 1) * 100;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("noCacheResult", noCacheResult);
        report.put("cacheResult", cacheResult);
        report.put("improvementRatio", String.format("%.2fx", improvementRatio));
        report.put("improvementPercent", String.format("%.2f%%", improvementPercent));
        report.put("qpsNoCache", String.format("%.2f", noCacheResult.getQps()));
        report.put("qpsWithCache", String.format("%.2f", cacheResult.getQps()));
        report.put("avgLatencyNoCache", String.format("%.2fms", noCacheResult.getAvgLatencyMs()));
        report.put("avgLatencyWithCache", String.format("%.2fms", cacheResult.getAvgLatencyMs()));
        report.put("p99LatencyNoCache", String.format("%.2fms", noCacheResult.getP99LatencyMs()));
        report.put("p99LatencyWithCache", String.format("%.2fms", cacheResult.getP99LatencyMs()));

        LOGGER.info("====== 商品查询压测完成 ======");
        LOGGER.info("无缓存QPS: {}, 有缓存QPS: {}, 提升: {}",
                noCacheResult.getQps(), cacheResult.getQps(), report.get("improvementRatio"));

        return report;
    }

    /**
     * 对话记忆压测：对比无缓存 vs Redis缓存
     *
     * @param totalRequests  总请求数
     * @param concurrency    并发数
     * @param noCacheQuery   无缓存查询逻辑（模拟从数据库加载对话历史）
     * @param sessionIds     对话会话ID列表
     * @return 无缓存和有缓存的压测结果对比
     */
    public Map<String, Object> benchmarkChatMemory(int totalRequests, int concurrency,
                                                     Supplier<Object> noCacheQuery, List<String> sessionIds) {
        LOGGER.info("====== 对话记忆压测开始 ======");
        LOGGER.info("总请求数: {}, 并发数: {}", totalRequests, concurrency);

        // 1. 清除缓存
        clearChatMemoryCache(sessionIds);

        // 2. 预热
        warmUp(noCacheQuery, 100);

        // 3. 无缓存基准测试：每次从数据库加载对话历史
        Supplier<Object> noCacheSupplier = () -> {
            try {
                return noCacheQuery.get();
            } catch (Exception e) {
                LOGGER.warn("无缓存对话记忆查询异常: {}", e.getMessage());
                return null;
            }
        };
        BenchmarkResult noCacheResult = runBenchmark("对话记忆-无缓存(数据库直连)",
                totalRequests, concurrency, noCacheSupplier);

        // 4. 预热Redis缓存（将对话记忆写入Redis）
        for (String sessionId : sessionIds) {
            String cacheKey = CHAT_MEMORY_CACHE_PREFIX + sessionId;
            Object chatData = noCacheQuery.get();
            if (chatData != null) {
                redisService.set(cacheKey, chatData, CACHE_EXPIRE_SECONDS);
            }
        }

        // 5. 有缓存测试：从Redis读取对话记忆
        Supplier<Object> cacheSupplier = () -> {
            try {
                String sid = sessionIds.get(ThreadLocalRandom.current().nextInt(sessionIds.size()));
                String cacheKey = CHAT_MEMORY_CACHE_PREFIX + sid;
                Object cached = redisService.get(cacheKey);
                if (cached != null) {
                    return cached;
                }
                // Cache miss: 查数据库并回填
                Object data = noCacheQuery.get();
                if (data != null) {
                    redisService.set(cacheKey, data, CACHE_EXPIRE_SECONDS);
                }
                return data;
            } catch (Exception e) {
                LOGGER.warn("缓存对话记忆查询异常: {}", e.getMessage());
                return null;
            }
        };
        BenchmarkResult cacheResult = runBenchmark("对话记忆-Redis缓存",
                totalRequests, concurrency, cacheSupplier);

        // 6. 计算提升比例
        double improvementRatio = noCacheResult.getQps() > 0
                ? cacheResult.getQps() / noCacheResult.getQps()
                : 0;
        double improvementPercent = (improvementRatio - 1) * 100;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("noCacheResult", noCacheResult);
        report.put("cacheResult", cacheResult);
        report.put("improvementRatio", String.format("%.2fx", improvementRatio));
        report.put("improvementPercent", String.format("%.2f%%", improvementPercent));
        report.put("qpsNoCache", String.format("%.2f", noCacheResult.getQps()));
        report.put("qpsWithCache", String.format("%.2f", cacheResult.getQps()));
        report.put("avgLatencyNoCache", String.format("%.2fms", noCacheResult.getAvgLatencyMs()));
        report.put("avgLatencyWithCache", String.format("%.2fms", cacheResult.getAvgLatencyMs()));
        report.put("p99LatencyNoCache", String.format("%.2fms", noCacheResult.getP99LatencyMs()));
        report.put("p99LatencyWithCache", String.format("%.2fms", cacheResult.getP99LatencyMs()));

        LOGGER.info("====== 对话记忆压测完成 ======");
        LOGGER.info("无缓存QPS: {}, 有缓存QPS: {}, 提升: {}",
                noCacheResult.getQps(), cacheResult.getQps(), report.get("improvementRatio"));

        return report;
    }

    /**
     * 预热方法：执行若干次调用以消除JIT编译等冷启动影响
     *
     * @param supplier  被调用的逻辑
     * @param warmUpTimes 预热次数
     */
    public void warmUp(Supplier<Object> supplier, int warmUpTimes) {
        LOGGER.info("预热开始，执行 {} 次...", warmUpTimes);
        for (int i = 0; i < warmUpTimes; i++) {
            try {
                supplier.get();
            } catch (Exception e) {
                // 预热期间忽略异常
            }
        }
        LOGGER.info("预热完成");
    }

    /**
     * 通用压测框架：使用线程池并发执行指定逻辑，统计QPS、延迟等指标
     *
     * @param testName       测试名称
     * @param totalRequests  总请求数
     * @param concurrency    并发数
     * @param supplier       被测逻辑
     * @return 压测结果
     */
    public BenchmarkResult runBenchmark(String testName, int totalRequests, int concurrency,
                                         Supplier<Object> supplier) {
        LOGGER.info("开始压测: {}", testName);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(totalRequests);
        AtomicLong completedCount = new AtomicLong(0);

        // 记录每次请求的延迟
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        // 提交所有任务
        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long startTime = System.nanoTime();
                    supplier.get();
                    long elapsed = System.nanoTime() - startTime;
                    latencies.add(elapsed);
                    completedCount.incrementAndGet();
                } catch (Exception e) {
                    LOGGER.warn("压测任务异常: {}", e.getMessage());
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // 统一触发开始
        long benchmarkStart = System.nanoTime();
        startLatch.countDown();

        try {
            finishLatch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("压测被中断", e);
        }

        long benchmarkEnd = System.nanoTime();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(benchmarkEnd - benchmarkStart);

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 计算统计指标
        double qps = durationMs > 0 ? (completedCount.get() * 1000.0 / durationMs) : 0;

        List<Long> latencyList = new ArrayList<>(latencies);
        Collections.sort(latencyList);

        double avgLatencyMs = latencyList.isEmpty() ? 0
                : latencyList.stream().mapToLong(l -> l).average().orElse(0) / 1_000_000.0;

        double p99LatencyMs = latencyList.isEmpty() ? 0
                : latencyList.get((int) (latencyList.size() * 0.99)) / 1_000_000.0;

        BenchmarkResult result = new BenchmarkResult(testName, totalRequests, concurrency,
                durationMs, qps, avgLatencyMs, p99LatencyMs);

        LOGGER.info("压测完成: {}", result);
        return result;
    }

    /**
     * 清除商品缓存
     */
    private void clearProductCache(List<Long> productIds) {
        for (Long pid : productIds) {
            redisService.del(PRODUCT_CACHE_PREFIX + pid);
        }
        LOGGER.info("已清除商品缓存, 共 {} 个key", productIds.size());
    }

    /**
     * 清除对话记忆缓存
     */
    private void clearChatMemoryCache(List<String> sessionIds) {
        for (String sid : sessionIds) {
            redisService.del(CHAT_MEMORY_CACHE_PREFIX + sid);
        }
        LOGGER.info("已清除对话记忆缓存, 共 {} 个key", sessionIds.size());
    }
}