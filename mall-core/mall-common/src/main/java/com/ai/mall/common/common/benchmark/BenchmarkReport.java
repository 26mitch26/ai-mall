package com.ai.mall.common.benchmark;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 压测报告数据类
 * 持久化到Redis，重启后数据不丢失，为"QPS提升300%"提供公开可查的数据支撑
 */
public class BenchmarkReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 报告唯一ID */
    private String reportId;

    /** 测试名称（如：商品查询、对话记忆） */
    private String testName;

    /** 测试时间戳 */
    private LocalDateTime timestamp;

    /** 无缓存QPS */
    private double noCacheQps;

    /** 有缓存QPS */
    private double cachedQps;

    /** 提升比例（如：4.00 表示提升4倍，即300%提升） */
    private double improvementRatio;

    /** 无缓存平均延迟(ms) */
    private double noCacheAvgLatency;

    /** 有缓存平均延迟(ms) */
    private double cachedAvgLatency;

    /** 无缓存P99延迟(ms) */
    private double noCacheP99Latency;

    /** 有缓存P99延迟(ms) */
    private double cachedP99Latency;

    /** 总请求数 */
    private int totalRequests;

    /** 并发数 */
    private int concurrency;

    /** 结论文字 */
    private String conclusion;

    public BenchmarkReport() {
        this.timestamp = LocalDateTime.now();
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public double getNoCacheQps() {
        return noCacheQps;
    }

    public void setNoCacheQps(double noCacheQps) {
        this.noCacheQps = noCacheQps;
    }

    public double getCachedQps() {
        return cachedQps;
    }

    public void setCachedQps(double cachedQps) {
        this.cachedQps = cachedQps;
    }

    public double getImprovementRatio() {
        return improvementRatio;
    }

    public void setImprovementRatio(double improvementRatio) {
        this.improvementRatio = improvementRatio;
    }

    public double getNoCacheAvgLatency() {
        return noCacheAvgLatency;
    }

    public void setNoCacheAvgLatency(double noCacheAvgLatency) {
        this.noCacheAvgLatency = noCacheAvgLatency;
    }

    public double getCachedAvgLatency() {
        return cachedAvgLatency;
    }

    public void setCachedAvgLatency(double cachedAvgLatency) {
        this.cachedAvgLatency = cachedAvgLatency;
    }

    public double getNoCacheP99Latency() {
        return noCacheP99Latency;
    }

    public void setNoCacheP99Latency(double noCacheP99Latency) {
        this.noCacheP99Latency = noCacheP99Latency;
    }

    public double getCachedP99Latency() {
        return cachedP99Latency;
    }

    public void setCachedP99Latency(double cachedP99Latency) {
        this.cachedP99Latency = cachedP99Latency;
    }

    public int getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(int totalRequests) {
        this.totalRequests = totalRequests;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    /**
     * 生成格式化的对比报告文本
     */
    public String formatReport() {
        double improvementPercent = (improvementRatio - 1) * 100;
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════════╗\n");
        sb.append("║              缓存性能压测报告 - ").append(testName).append("\n");
        sb.append("╠══════════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  报告ID:     %-50s ║%n", reportId));
        sb.append(String.format("║  测试时间:   %-50s ║%n",
                timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        sb.append(String.format("║  总请求数:   %-50d ║%n", totalRequests));
        sb.append(String.format("║  并发数:     %-50d ║%n", concurrency));
        sb.append("╠══════════════════════════════════════════════════════════════════╣\n");
        sb.append("║  性能对比:                                                      ║\n");
        sb.append(String.format("║    无缓存 QPS:        %-42.2f ║%n", noCacheQps));
        sb.append(String.format("║    有缓存 QPS:        %-42.2f ║%n", cachedQps));
        sb.append(String.format("║    QPS提升比例:       %-42s ║%n",
                String.format("%.2fx (提升%.0f%%)", improvementRatio, improvementPercent)));
        sb.append("╠══════════════════════════════════════════════════════════════════╣\n");
        sb.append("║  延迟对比:                                                      ║\n");
        sb.append(String.format("║    无缓存 平均延迟:   %-42.2f ms ║%n", noCacheAvgLatency));
        sb.append(String.format("║    有缓存 平均延迟:   %-42.2f ms ║%n", cachedAvgLatency));
        sb.append(String.format("║    无缓存 P99延迟:    %-42.2f ms ║%n", noCacheP99Latency));
        sb.append(String.format("║    有缓存 P99延迟:    %-42.2f ms ║%n", cachedP99Latency));
        sb.append("╠══════════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  结论: %-57s ║%n", conclusion));
        sb.append("╚══════════════════════════════════════════════════════════════════╝\n");
        return sb.toString();
    }
}
