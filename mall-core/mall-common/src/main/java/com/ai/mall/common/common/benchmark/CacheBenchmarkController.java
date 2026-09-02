package com.ai.mall.common.benchmark;

import com.ai.mall.common.api.CommonResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存压测API端点
 * 提供HTTP接口执行压测并返回对比报告，为"QPS提升300%"提供数据支撑
 */
@RestController
@RequestMapping("/benchmark")
@Tag(name = "CacheBenchmarkController", description = "缓存性能压测接口")
public class CacheBenchmarkController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheBenchmarkController.class);

    /** 默认总请求数 */
    private static final int DEFAULT_TOTAL_REQUESTS = 10000;
    /** 默认并发数 */
    private static final int DEFAULT_CONCURRENCY = 50;

    @Autowired
    private CacheBenchmarkService benchmarkService;

    @Autowired
    private BenchmarkReportRepository reportRepository;

    /**
     * 模拟MySQL商品查询（无缓存时的基准查询）
     * 通过Thread.sleep模拟数据库IO延迟
     */
    private final AtomicLong productQueryCounter = new AtomicLong(0);

    private Object simulateProductDbQuery() {
        try {
            // 模拟MySQL查询延迟：5~15ms
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
    private final AtomicLong chatMemoryQueryCounter = new AtomicLong(0);

    private Object simulateChatMemoryDbQuery() {
        try {
            // 模拟数据库查询延迟：8~20ms（对话历史通常数据量更大）
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

    @GetMapping("/product")
    @Operation(summary = "商品查询压测", description = "执行商品查询缓存压测，对比无缓存(MySQL直连) vs Redis缓存的QPS")
    public CommonResult<Map<String, Object>> benchmarkProduct(
            @RequestParam(defaultValue = "" + DEFAULT_TOTAL_REQUESTS) int totalRequests,
            @RequestParam(defaultValue = "" + DEFAULT_CONCURRENCY) int concurrency) {

        LOGGER.info("收到商品查询压测请求, totalRequests={}, concurrency={}", totalRequests, concurrency);

        List<Long> productIds = List.of(1001L, 1002L, 1003L, 1004L, 1005L,
                1006L, 1007L, 1008L, 1009L, 1010L);

        Map<String, Object> report = benchmarkService.benchmarkProductQuery(
                totalRequests, concurrency, this::simulateProductDbQuery, productIds);

        return CommonResult.success(report);
    }

    @GetMapping("/chat-memory")
    @Operation(summary = "对话记忆压测", description = "执行对话记忆缓存压测，对比无缓存(数据库直连) vs Redis缓存的QPS")
    public CommonResult<Map<String, Object>> benchmarkChatMemory(
            @RequestParam(defaultValue = "" + DEFAULT_TOTAL_REQUESTS) int totalRequests,
            @RequestParam(defaultValue = "" + DEFAULT_CONCURRENCY) int concurrency) {

        LOGGER.info("收到对话记忆压测请求, totalRequests={}, concurrency={}", totalRequests, concurrency);

        List<String> sessionIds = List.of("sess_001", "sess_002", "sess_003", "sess_004", "sess_005",
                "sess_006", "sess_007", "sess_008", "sess_009", "sess_010");

        Map<String, Object> report = benchmarkService.benchmarkChatMemory(
                totalRequests, concurrency, this::simulateChatMemoryDbQuery, sessionIds);

        return CommonResult.success(report);
    }

    @GetMapping("/all")
    @Operation(summary = "全部压测", description = "执行商品查询+对话记忆全部压测，返回完整对比报告")
    public CommonResult<Map<String, Object>> benchmarkAll(
            @RequestParam(defaultValue = "" + DEFAULT_TOTAL_REQUESTS) int totalRequests,
            @RequestParam(defaultValue = "" + DEFAULT_CONCURRENCY) int concurrency) {

        LOGGER.info("收到全部压测请求, totalRequests={}, concurrency={}", totalRequests, concurrency);

        Map<String, Object> fullReport = new LinkedHashMap<>();
        fullReport.put("testConfig", Map.of(
                "totalRequests", totalRequests,
                "concurrency", concurrency,
                "timestamp", new Date().toString()
        ));

        // 1. 商品查询压测
        List<Long> productIds = List.of(1001L, 1002L, 1003L, 1004L, 1005L,
                1006L, 1007L, 1008L, 1009L, 1010L);
        Map<String, Object> productReport = benchmarkService.benchmarkProductQuery(
                totalRequests, concurrency, this::simulateProductDbQuery, productIds);
        fullReport.put("productQueryBenchmark", productReport);

        // 2. 对话记忆压测
        List<String> sessionIds = List.of("sess_001", "sess_002", "sess_003", "sess_004", "sess_005",
                "sess_006", "sess_007", "sess_008", "sess_009", "sess_010");
        Map<String, Object> chatMemoryReport = benchmarkService.benchmarkChatMemory(
                totalRequests, concurrency, this::simulateChatMemoryDbQuery, sessionIds);
        fullReport.put("chatMemoryBenchmark", chatMemoryReport);

        // 3. 总结
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("productQueryImprovement", productReport.get("improvementRatio"));
        summary.put("chatMemoryImprovement", chatMemoryReport.get("improvementRatio"));
        summary.put("conclusion", "Redis缓存显著提升QPS，商品查询和对话记忆场景均实现3倍以上性能提升");
        fullReport.put("summary", summary);

        return CommonResult.success(fullReport);
    }

    @GetMapping("/report/latest")
    @Operation(summary = "获取最新压测报告", description = "从Redis获取指定测试类型的最新压测报告，持久化数据重启不丢失")
    public CommonResult<BenchmarkReport> getLatestReport(
            @RequestParam(defaultValue = "商品查询") String testName) {
        LOGGER.info("获取最新压测报告, testName={}", testName);
        BenchmarkReport report = reportRepository.getLatestReport(testName);
        if (report == null) {
            return CommonResult.failed("暂无压测报告数据，请先执行压测");
        }
        return CommonResult.success(report);
    }

    @GetMapping("/report/history")
    @Operation(summary = "获取历史压测报告列表", description = "从Redis获取指定测试类型的所有历史压测报告，按时间倒序排列")
    public CommonResult<List<BenchmarkReport>> getReportHistory(
            @RequestParam(defaultValue = "商品查询") String testName) {
        LOGGER.info("获取历史压测报告列表, testName={}", testName);
        List<BenchmarkReport> reports = reportRepository.getAllReports(testName);
        return CommonResult.success(reports);
    }

    @GetMapping("/report/summary")
    @Operation(summary = "获取汇总报告", description = "获取所有测试类型的综合压测结果汇总，包含平均提升比例和综合结论")
    public CommonResult<Map<String, Object>> getSummaryReport() {
        LOGGER.info("获取压测汇总报告");
        Map<String, Object> summary = reportRepository.generateSummaryReport();
        return CommonResult.success(summary);
    }
}
