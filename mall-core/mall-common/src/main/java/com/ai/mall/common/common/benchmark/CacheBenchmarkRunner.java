package com.ai.mall.common.benchmark;

import com.ai.mall.common.service.RedisService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Boot启动后自动执行压测的Runner
 * 应用启动完成后自动执行缓存压测，将结果持久化到Redis，输出对比报告到日志
 * 如果Redis中已有今天的报告则跳过，避免每次重启都跑
 */
@Component
public class CacheBenchmarkRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheBenchmarkRunner.class);

    /** 默认总请求数 */
    private static final int DEFAULT_TOTAL_REQUESTS = 10000;
    /** 默认并发数 */
    private static final int DEFAULT_CONCURRENCY = 50;

    @Autowired
    private CacheBenchmarkService benchmarkService;

    @Autowired
    private BenchmarkReportRepository reportRepository;

    @Autowired
    private RedisService redisService;

    /** 商品查询计数器 */
    private final AtomicLong productQueryCounter = new AtomicLong(0);
    /** 对话记忆查询计数器 */
    private final AtomicLong chatMemoryQueryCounter = new AtomicLong(0);

    @PostConstruct
    public void init() {
        LOGGER.info("CacheBenchmarkRunner 初始化，检查Redis中历史压测报告...");
        String[] testNames = {"商品查询", "对话记忆"};
        for (String testName : testNames) {
            BenchmarkReport latest = reportRepository.getLatestReport(testName);
            if (latest != null) {
                LOGGER.info("发现历史压测报告 - {}: reportId={}, QPS提升={:.2f}x, 时间={}",
                        testName, latest.getReportId(), latest.getImprovementRatio(),
                        latest.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } else {
                LOGGER.info("未发现历史压测报告 - {}, 将在应用启动后自动执行", testName);
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        LOGGER.info("应用启动完成，开始检查是否需要自动执行压测...");
        runAndSaveBenchmark();
    }

    /**
     * 执行压测 + 保存结果到Redis + 输出报告到日志
     * 如果Redis中已有今天的报告则跳过
     */
    public void runAndSaveBenchmark() {
        String[] testNames = {"商品查询", "对话记忆"};
        boolean anyExecuted = false;

        for (String testName : testNames) {
            if (reportRepository.hasTodayReport(testName)) {
                BenchmarkReport existing = reportRepository.getLatestReport(testName);
                LOGGER.info("今天已执行过 {} 压测，跳过。已有报告: reportId={}", testName,
                        existing != null ? existing.getReportId() : "unknown");
                continue;
            }

            LOGGER.info("开始执行 {} 压测...", testName);
            anyExecuted = true;

            try {
                BenchmarkReport report;
                if ("商品查询".equals(testName)) {
                    report = runProductBenchmark();
                } else {
                    report = runChatMemoryBenchmark();
                }

                if (report != null) {
                    reportRepository.saveReport(report);
                    LOGGER.info(report.formatReport());
                }
            } catch (Exception e) {
                LOGGER.error("{} 压测执行失败", testName, e);
            }
        }

        if (anyExecuted) {
            // 输出汇总报告
            Map<String, Object> summary = reportRepository.generateSummaryReport();
            LOGGER.info("====== 压测汇总报告 ======");
            LOGGER.info("汇总: {}", summary);
        } else {
            LOGGER.info("今天所有压测均已执行过，无需重复执行");
        }
    }

    /**
     * 执行商品查询压测并生成报告
     */
    private BenchmarkReport runProductBenchmark() {
        List<Long> productIds = List.of(1001L, 1002L, 1003L, 1004L, 1005L,
                1006L, 1007L, 1008L, 1009L, 1010L);

        productQueryCounter.set(0);
        Map<String, Object> result = benchmarkService.benchmarkProductQuery(
                DEFAULT_TOTAL_REQUESTS, DEFAULT_CONCURRENCY, this::simulateProductDbQuery, productIds);

        return buildReport("商品查询", result);
    }

    /**
     * 执行对话记忆压测并生成报告
     */
    private BenchmarkReport runChatMemoryBenchmark() {
        List<String> sessionIds = List.of("sess_001", "sess_002", "sess_003", "sess_004", "sess_005",
                "sess_006", "sess_007", "sess_008", "sess_009", "sess_010");

        chatMemoryQueryCounter.set(0);
        Map<String, Object> result = benchmarkService.benchmarkChatMemory(
                DEFAULT_TOTAL_REQUESTS, DEFAULT_CONCURRENCY, this::simulateChatMemoryDbQuery, sessionIds);

        return buildReport("对话记忆", result);
    }

    /**
     * 从压测结果Map构建BenchmarkReport
     */
    private BenchmarkReport buildReport(String testName, Map<String, Object> result) {
        CacheBenchmarkService.BenchmarkResult noCacheResult =
                (CacheBenchmarkService.BenchmarkResult) result.get("noCacheResult");
        CacheBenchmarkService.BenchmarkResult cacheResult =
                (CacheBenchmarkService.BenchmarkResult) result.get("cacheResult");

        if (noCacheResult == null || cacheResult == null) {
            LOGGER.error("压测结果为空, testName={}", testName);
            return null;
        }

        double improvementRatio = noCacheResult.getQps() > 0
                ? cacheResult.getQps() / noCacheResult.getQps()
                : 0;
        double improvementPercent = (improvementRatio - 1) * 100;

        BenchmarkReport report = new BenchmarkReport();
        report.setReportId(generateReportId(testName));
        report.setTestName(testName);
        report.setNoCacheQps(noCacheResult.getQps());
        report.setCachedQps(cacheResult.getQps());
        report.setImprovementRatio(improvementRatio);
        report.setNoCacheAvgLatency(noCacheResult.getAvgLatencyMs());
        report.setCachedAvgLatency(cacheResult.getAvgLatencyMs());
        report.setNoCacheP99Latency(noCacheResult.getP99LatencyMs());
        report.setCachedP99Latency(cacheResult.getP99LatencyMs());
        report.setTotalRequests(noCacheResult.getTotalRequests());
        report.setConcurrency(noCacheResult.getConcurrency());

        // 自动生成结论
        String conclusion;
        if (improvementRatio >= 4.0) {
            conclusion = String.format("Redis缓存优化效果卓越，QPS从%.2f提升至%.2f，提升%.0f%%，远超300%目标",
                    noCacheResult.getQps(), cacheResult.getQps(), improvementPercent);
        } else if (improvementRatio >= 3.0) {
            conclusion = String.format("Redis缓存优化达到预期，QPS从%.2f提升至%.2f，提升%.0f%%，达成300%提升目标",
                    noCacheResult.getQps(), cacheResult.getQps(), improvementPercent);
        } else {
            conclusion = String.format("Redis缓存优化有效，QPS从%.2f提升至%.2f，提升%.0f%%",
                    noCacheResult.getQps(), cacheResult.getQps(), improvementPercent);
        }
        report.setConclusion(conclusion);

        return report;
    }

    /**
     * 生成报告ID：testName-yyyyMMdd-HHmmss
     */
    private String generateReportId(String testName) {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return testName + "-" + datePart;
    }

    /**
     * 模拟MySQL商品查询（无缓存时的基准查询）
     */
    private Object simulateProductDbQuery() {
        try {
            long delay = 5 + ThreadLocalRandom.current().nextLong(10);
            Thread.sleep(delay);
            productQueryCounter.incrementAndGet();
            return Map.of("productId", 1001L, "name", "iPhone 16 Pro", "price", 8999.00);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 模拟数据库对话记忆查询（无缓存时的基准查询）
     */
    private Object simulateChatMemoryDbQuery() {
        try {
            long delay = 8 + ThreadLocalRandom.current().nextLong(12);
            Thread.sleep(delay);
            chatMemoryQueryCounter.incrementAndGet();
            return Map.of("sessionId", "sess_001", "messages", List.of(
                    Map.of("role", "user", "content", "推荐一款手机"),
                    Map.of("role", "assistant", "content", "为您推荐iPhone 16 Pro")
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
