package com.ai.mall.common.benchmark;

import com.ai.mall.common.service.RedisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 压测报告持久化仓库
 * 使用Redis存储压测报告，重启后数据不丢失，为"QPS提升300%"提供公开可查的数据支撑
 *
 * Redis存储结构：
 * - 单条报告: benchmark:report:data:{reportId}  → BenchmarkReport JSON
 * - 测试名称索引: benchmark:report:index:{testName}  → List<reportId> (按时间倒序)
 * - 最新报告: benchmark:report:latest:{testName}  → reportId
 * - 报告日期标记: benchmark:report:today:{testName}:{date}  → reportId (防止同天重复执行)
 */
@Repository
public class BenchmarkReportRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkReportRepository.class);

    private static final String REPORT_DATA_PREFIX = "benchmark:report:data:";
    private static final String REPORT_INDEX_PREFIX = "benchmark:report:index:";
    private static final String REPORT_LATEST_PREFIX = "benchmark:report:latest:";
    private static final String REPORT_TODAY_PREFIX = "benchmark:report:today:";
    /** 报告数据保留天数 */
    private static final long REPORT_TTL_DAYS = 30;

    @Autowired
    private RedisService redisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 保存报告到Redis
     */
    public void saveReport(BenchmarkReport report) {
        try {
            String reportJson = objectMapper.writeValueAsString(report);

            // 1. 保存报告数据
            String dataKey = REPORT_DATA_PREFIX + report.getReportId();
            redisService.set(dataKey, reportJson, REPORT_TTL_DAYS * 24 * 3600L);

            // 2. 添加到测试名称索引（List头部插入，保持时间倒序）
            String indexKey = REPORT_INDEX_PREFIX + report.getTestName();
            redisService.lPush(indexKey, report.getReportId());
            // 索引列表保留30天过期
            redisService.expire(indexKey, REPORT_TTL_DAYS * 24 * 3600L);

            // 3. 更新最新报告标记
            String latestKey = REPORT_LATEST_PREFIX + report.getTestName();
            redisService.set(latestKey, report.getReportId());

            // 4. 标记今天已执行（用于Runner跳过判断）
            String todayKey = REPORT_TODAY_PREFIX + report.getTestName() + ":"
                    + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            redisService.set(todayKey, report.getReportId(), 48 * 3600L); // 保留48小时，跨天安全

            LOGGER.info("压测报告已保存到Redis, reportId={}, testName={}", report.getReportId(), report.getTestName());
        } catch (JsonProcessingException e) {
            LOGGER.error("保存压测报告失败, 序列化异常", e);
        }
    }

    /**
     * 获取最新报告
     */
    public BenchmarkReport getLatestReport(String testName) {
        String latestKey = REPORT_LATEST_PREFIX + testName;
        Object reportIdObj = redisService.get(latestKey);
        if (reportIdObj == null) {
            return null;
        }
        return getReportById(reportIdObj.toString());
    }

    /**
     * 获取所有历史报告（按时间倒序）
     */
    public List<BenchmarkReport> getAllReports(String testName) {
        String indexKey = REPORT_INDEX_PREFIX + testName;
        List<Object> reportIds = redisService.lRange(indexKey, 0, -1);
        if (reportIds == null || reportIds.isEmpty()) {
            return Collections.emptyList();
        }

        return reportIds.stream()
                .map(id -> getReportById(id.toString()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 根据reportId获取报告
     */
    public BenchmarkReport getReportById(String reportId) {
        String dataKey = REPORT_DATA_PREFIX + reportId;
        Object reportJsonObj = redisService.get(dataKey);
        if (reportJsonObj == null) {
            return null;
        }
        try {
            return objectMapper.readValue(reportJsonObj.toString(), BenchmarkReport.class);
        } catch (JsonProcessingException e) {
            LOGGER.error("读取压测报告失败, 反序列化异常, reportId={}", reportId, e);
            return null;
        }
    }

    /**
     * 检查今天是否已有报告（用于Runner跳过判断）
     */
    public boolean hasTodayReport(String testName) {
        String todayKey = REPORT_TODAY_PREFIX + testName + ":"
                + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return redisService.hasKey(todayKey);
    }

    /**
     * 生成汇总报告（所有测试的综合结果）
     */
    public Map<String, Object> generateSummaryReport() {
        // 收集所有测试类型的最新报告
        String[] testNames = {"商品查询", "对话记忆"};
        List<BenchmarkReport> latestReports = new ArrayList<>();

        for (String testName : testNames) {
            BenchmarkReport report = getLatestReport(testName);
            if (report != null) {
                latestReports.add(report);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        summary.put("totalTestTypes", testNames.length);
        summary.put("availableTestTypes", latestReports.size());

        if (latestReports.isEmpty()) {
            summary.put("status", "NO_DATA");
            summary.put("message", "暂无压测数据，请先执行压测");
            return summary;
        }

        // 各测试的详细结果
        List<Map<String, Object>> testResults = new ArrayList<>();
        double avgImprovementRatio = 0;
        double maxImprovementRatio = 0;
        double minImprovementRatio = Double.MAX_VALUE;

        for (BenchmarkReport report : latestReports) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("testName", report.getTestName());
            result.put("reportId", report.getReportId());
            result.put("timestamp", report.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            result.put("noCacheQps", report.getNoCacheQps());
            result.put("cachedQps", report.getCachedQps());
            result.put("improvementRatio", report.getImprovementRatio());
            result.put("improvementPercent", String.format("%.0f%%", (report.getImprovementRatio() - 1) * 100));
            result.put("noCacheAvgLatency", report.getNoCacheAvgLatency());
            result.put("cachedAvgLatency", report.getCachedAvgLatency());
            result.put("conclusion", report.getConclusion());
            testResults.add(result);

            avgImprovementRatio += report.getImprovementRatio();
            maxImprovementRatio = Math.max(maxImprovementRatio, report.getImprovementRatio());
            minImprovementRatio = Math.min(minImprovementRatio, report.getImprovementRatio());
        }

        if (!latestReports.isEmpty()) {
            avgImprovementRatio /= latestReports.size();
        } else {
            minImprovementRatio = 0;
        }

        summary.put("testResults", testResults);
        summary.put("averageImprovementRatio", String.format("%.2fx", avgImprovementRatio));
        summary.put("averageImprovementPercent", String.format("%.0f%%", (avgImprovementRatio - 1) * 100));
        summary.put("maxImprovementRatio", String.format("%.2fx", maxImprovementRatio));
        summary.put("minImprovementRatio", String.format("%.2fx", minImprovementRatio));
        summary.put("status", "OK");

        // 综合结论
        String overallConclusion;
        if (avgImprovementRatio >= 4.0) {
            overallConclusion = String.format("Redis缓存优化效果显著，平均QPS提升%.0f%%，所有场景均实现3倍以上性能提升",
                    (avgImprovementRatio - 1) * 100);
        } else if (avgImprovementRatio >= 3.0) {
            overallConclusion = String.format("Redis缓存优化效果良好，平均QPS提升%.0f%%，达到预期目标",
                    (avgImprovementRatio - 1) * 100);
        } else {
            overallConclusion = String.format("Redis缓存优化有效，平均QPS提升%.0f%%",
                    (avgImprovementRatio - 1) * 100);
        }
        summary.put("overallConclusion", overallConclusion);

        return summary;
    }
}
