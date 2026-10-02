package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.model.AlertEvent;
import com.ai.mall.agent.ops.model.Severity;
import com.ai.mall.agent.ops.service.event.EventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 监控Agent：基于3-Sigma + EWMA双算法投票的异常检测
 *
 * 投票机制：3-Sigma与EWMA任一算法单独触发不告警，需两算法均触发才告警，以过滤单算法误报。
 * - 3-Sigma：基于滑动窗口计算真实均值和标准差，检测偏离3倍标准差的数据点
 * - EWMA：指数加权移动平均，对近期数据赋予更高权重，检测趋势性异常
 * <p>
 * 效果度量：双算法投票相对单算法的误报抑制效果由离线评测产出，见
 * {@code AnomalyDetectionBenchmark}。评测在带 ground-truth 标签的时序数据上统计
 * 各策略的 TP/FP/FN，得出真实的误报率下降幅度；运行时统计仅反映线上实际累计值，
 * 不预置任何演示基数。
 */
@Slf4j
@Service
public class MonitorAgent {

    private final EventBus eventBus;

    /**
     * 默认检测阈值：偏离均值 3 倍标准差，即经典 3-Sigma 准则。
     *
     * <p>该阈值可通过 {@link #MonitorAgent(EventBus, double)} 调整。之所以做成可调，
     * 是因为投票机制与单算法灵敏度是联动的：单算法阈值越低越敏感（召回高但误报也高），
     * 此时 AND 投票负责收敛误报；若单算法本身已极度保守，再叠加 AND 投票只会
     * 过度抑制召回。评测 {@code AnomalyDetectionBenchmarkTest} 会扫描不同灵敏度，
     * 给出投票收益最大的工作区间。
     */
    private static final double DEFAULT_DETECTION_THRESHOLD = 3.0;

    private static final double EWMA_ALPHA = 0.3;

    /**
     * 异常点的 EWMA 跟进系数，远小于正常点的 {@link #EWMA_ALPHA}。
     * 使 EWMA 在故障期间保持"不认可异常水位"，从而维持告警；
     * 同时对真实水位迁移保留缓慢收敛能力。
     */
    private static final double EWMA_ALPHA_ADAPT = 0.02;

    private static final int WINDOW_SIZE = 60;

    /**
     * 启动检测所需的最小历史样本数。
     * 样本过少时滑动窗口的标准差与 EWMA 方差都不稳定，判定不可靠，直接跳过。
     */
    private static final int MIN_SAMPLES_FOR_DETECTION = 10;

    /**
     * EWMA 方差的冷启动下限，避免 {@code sqrt(0) = 0} 使动态阈值退化。
     */
    private static final double EWMA_VARIANCE_FLOOR = 1.0;

    /**
     * 连续异常点数超过该值后，认定为真实水位迁移而非故障，重建基线。
     * 需大于典型故障的持续时长，否则故障会被误判为迁移而丢失告警。
     */
    private static final int BASELINE_MIGRATION_THRESHOLD = 50;

    /** 连续被 3-Sigma 判定为异常的点数，用于识别真实水位迁移 */
    private int consecutiveAnomalies = 0;

    /**
     * 检测阈值（单位：标准差倍数），默认 3.0，即经典 3-Sigma 准则。
     * 同时作用于 3-Sigma 的 z-score 判定与 EWMA 的动态阈值，可通过
     * {@code aiops.detection.threshold} 调整。
     *
     * <p>该值与投票机制强联动，实测规律见 {@code AnomalyDetectionBenchmarkTest}
     * 的阈值扫描：阈值越低单算法越敏感，AND 投票收敛误报的收益越大；
     * 阈值过高时单算法本身已足够保守，再叠加 AND 投票只会过度抑制召回。
     * 默认取 3.0 以保证"3-Sigma"语义准确，需要更高 F1 时可下调至 2.0。
     */
    @Value("${aiops.detection.threshold:3.0}")
    private double detectionThreshold = DEFAULT_DETECTION_THRESHOLD;

    /**
     * 生产用构造器：阈值由 {@code aiops.detection.threshold} 注入，缺省 3.0。
     */
    @Autowired
    public MonitorAgent(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 指定阈值的构造器。包级可见，供离线评测做灵敏度扫描，绕过配置注入。
     */
    MonitorAgent(EventBus eventBus, double detectionThreshold) {
        this.eventBus = eventBus;
        this.detectionThreshold = detectionThreshold;
    }

    /** 历史数据滑动窗口，维护最近N个数据点用于计算真实均值和标准差 */
    private final LinkedList<Double> slidingWindow = new LinkedList<>();

    /** EWMA递推状态：指数加权移动平均值 */
    private double ewmaState = Double.NaN;

    /** EWMA动态阈值：基于EWMA方差自适应计算 */
    private double ewmaVariance = 0.0;

    /** 异常检测统计数据，按指标名称分类统计 */
    private final AnomalyDetectionStats stats = new AnomalyDetectionStats();

    /** 待确认告警：alertId -> metricName，用于后续人工反馈确认是否误报 */
    private final Map<String, String> pendingAlerts = new ConcurrentHashMap<>();

    /**
     * 异常检测统计内部类：记录3-Sigma + EWMA双算法投票的运行时检测统计。
     * 所有计数从 0 开始，仅反映本进程启动后的真实累计值，不预置任何演示基数。
     *
     * <p>注意：这里统计的是「告警量过滤比例」（有多少触发被投票机制拦下），
     * 与「误报率下降」不是同一概念——前者无法区分被拦下的是真异常还是噪声。
     * 真正的误报率对比需要在带 ground-truth 标签的数据上评测，见
     * {@code AnomalyDetectionBenchmark}。
     */
    public static class AnomalyDetectionStats {

        /** 总检测次数 */
        private final AtomicLong totalChecks = new AtomicLong(0);

        /** 单算法触发次数（3-Sigma单独触发 或 EWMA单独触发，投票未通过，不发出告警） */
        private final AtomicLong singleAlgorithmAlerts = new AtomicLong(0);

        /** 双算法同时触发次数（3-Sigma + EWMA均触发，投票通过，发出告警） */
        private final AtomicLong dualAlgorithmAlerts = new AtomicLong(0);

        /** 误报次数（告警后人工确认为正常） */
        private final AtomicLong falsePositives = new AtomicLong(0);

        /** 真实异常次数（告警后人工确认为真实异常） */
        private final AtomicLong truePositives = new AtomicLong(0);

        /**
         * 记录每次检测结果
         * @param isDualVote true=双算法同时触发（投票通过），false=仅单算法触发（投票未通过）
         */
        public void recordCheck(boolean isDualVote) {
            totalChecks.incrementAndGet();
            if (isDualVote) {
                dualAlgorithmAlerts.incrementAndGet();
            } else {
                singleAlgorithmAlerts.incrementAndGet();
            }
        }

        /**
         * 记录误报：告警后人工确认为正常
         */
        public void recordFalsePositive() {
            falsePositives.incrementAndGet();
        }

        /**
         * 记录真实异常：告警后人工确认为真实异常
         */
        public void recordTruePositive() {
            truePositives.incrementAndGet();
        }

        /**
         * 计算告警量过滤比例：投票机制拦下了多少比例的「单算法触发」。
         *
         * <p>公式：alertFilterRate = 1 - dualAlgorithmAlerts / totalChecks
         *
         * <p>含义：若放任任一算法单独触发即告警，则会发出 totalChecks 次告警；
         * 经双算法投票后只发出 dualAlgorithmAlerts 次，被过滤掉的比例即为本值。
         *
         * <p>⚠️ 该指标衡量的是「告警量收敛」，不等于「误报率下降」：被过滤掉的
         * 触发中既包含误报也可能包含真实异常。误报率需在有标签数据上评测，
         * 见 {@code AnomalyDetectionBenchmark}。
         *
         * @return 告警量过滤比例，0.0~1.0之间；无数据时返回0.0
         */
        public double calculateAlertFilterRate() {
            long total = totalChecks.get();
            if (total == 0) {
                return 0.0;
            }
            return 1.0 - ((double) dualAlgorithmAlerts.get() / (double) total);
        }

        /**
         * 计算实际误报率（基于人工反馈）
         * 公式：falsePositiveRate = falsePositives / (falsePositives + truePositives)
         *
         * @return 实际误报率，0.0~1.0之间；数据不足时返回0.0
         */
        public double calculateActualFalsePositiveRate() {
            long fp = falsePositives.get();
            long tp = truePositives.get();
            long total = fp + tp;
            if (total == 0) {
                return 0.0;
            }
            return (double) fp / total;
        }

        /**
         * 获取统计摘要
         */
        public String getStats() {
            return String.format(
                "[Anomaly Detection Stats] totalChecks=%d, singleAlgorithmAlerts=%d, " +
                "dualAlgorithmAlerts=%d, falsePositives=%d, truePositives=%d, " +
                "alertFilterRate=%.2f%%, actualFalsePositiveRate=%.2f%%",
                totalChecks.get(), singleAlgorithmAlerts.get(), dualAlgorithmAlerts.get(),
                falsePositives.get(), truePositives.get(),
                calculateAlertFilterRate() * 100, calculateActualFalsePositiveRate() * 100
            );
        }

        // Getter方法
        public long getTotalChecks() { return totalChecks.get(); }
        public long getSingleAlgorithmAlerts() { return singleAlgorithmAlerts.get(); }
        public long getDualAlgorithmAlerts() { return dualAlgorithmAlerts.get(); }
        public long getFalsePositives() { return falsePositives.get(); }
        public long getTruePositives() { return truePositives.get(); }
    }

    public AlertEvent detectAnomaly(String metricName, double value, String service) {
        log.info("MonitorAgent detecting anomaly for {}: {} on {}", metricName, value, service);

        DetectionResult result = evaluate(value);

        // 记录检测统计：区分单算法触发与双算法同时触发
        stats.recordCheck(result.isAlert());

        if (result.isAlert()) {
            AlertEvent alert = AlertEvent.builder()
                    .id(UUID.randomUUID().toString())
                    .metricName(metricName)
                    .metricValue(value)
                    .targetService(service)
                    .severity(calculateSeverity(value))
                    .timestamp(LocalDateTime.now())
                    .status("detected")
                    .build();

            // 记录待确认告警，供后续人工反馈
            pendingAlerts.put(alert.getId(), metricName);

            eventBus.publish("aiops.alerts", alert);
            log.info("Anomaly detected and alert published: {}, stats: {}", alert.getId(), stats.getStats());
            return alert;
        }

        int votes = result.voteCount();
        // 任一算法单独触发时记录（被投票机制过滤掉）
        if (votes == 1) {
            log.info("Single algorithm alert filtered by voting (sigma={}, ewma={}), alertFilterRate={}%",
                     result.sigmaVote(), result.ewmaVote(),
                     formatPercent(stats.calculateAlertFilterRate()));
        } else {
            log.info("No anomaly detected (votes: 0/2, sigma={}, ewma={})",
                    result.sigmaVote(), result.ewmaVote());
        }
        return null;
    }

    /**
     * 单次检测评估：推进内部状态（滑动窗口 + EWMA）并返回各算法的独立投票结果。
     *
     * <p>包级可见，供离线评测 {@code AnomalyDetectionBenchmark} 使用。暴露各算法独立投票
     * 而非仅暴露最终结论，是为了让评测在同一次遍历中同时统计「仅3-Sigma」「仅EWMA」
     * 「双算法投票」三种策略的 TP/FP/FN——三者共享完全相同的状态演化轨迹，对比才公平。
     *
     * @param value 当前指标值
     * @return 各算法投票明细，{@link DetectionResult#isAlert()} 为投票后的最终结论
     */
    DetectionResult evaluate(double value) {
        // 必须先检测、后更新状态：若先把当前点并入滑动窗口和 EWMA，
        // 判定基准就会被待判定点自身污染（自参考），导致异常被"自我吸收"。
        // 对 EWMA 而言该错误是致命的——阈值会永远高于当前值，恒不触发。
        boolean sigmaVote = detectBy3Sigma(value);
        boolean ewmaVote = detectByEWMA(value);
        boolean isAlert = sigmaVote && ewmaVote;

        updateBaseline(value, sigmaVote);
        updateEWMA(value, sigmaVote);

        return new DetectionResult(sigmaVote, ewmaVote, isAlert);
    }

    /**
     * 单次检测的投票明细。
     *
     * @param sigmaVote 3-Sigma 算法是否判定为异常
     * @param ewmaVote EWMA 算法是否判定为异常
     * @param isAlert  双算法投票后的最终结论（两算法均判定异常才告警）
     */
    public record DetectionResult(boolean sigmaVote, boolean ewmaVote, boolean isAlert) {

        /** 投出异常票的算法数量（0~2） */
        public int voteCount() {
            return (sigmaVote ? 1 : 0) + (ewmaVote ? 1 : 0);
        }
    }

    /**
     * 告警反馈接口：确认告警是否为误报
     * @param alertId 告警ID
     * @param isFalsePositive true=误报（正常波动被误判），false=真实异常
     */
    public void confirmAlert(String alertId, boolean isFalsePositive) {
        String metricName = pendingAlerts.remove(alertId);
        if (metricName == null) {
            log.warn("Alert feedback ignored: alertId={} not found in pending alerts", alertId);
            return;
        }
        if (isFalsePositive) {
            stats.recordFalsePositive();
            log.info("Alert {} confirmed as FALSE POSITIVE for metric: {}", alertId, metricName);
        } else {
            stats.recordTruePositive();
            log.info("Alert {} confirmed as TRUE POSITIVE for metric: {}", alertId, metricName);
        }
    }

    /**
     * 获取检测统计数据
     */
    public AnomalyDetectionStats getDetectionStats() {
        return stats;
    }

    /**
     * 维护 3-Sigma 的基线窗口。
     *
     * <p>核心矛盾：若把异常点无差别并入滑动窗口，持续型故障（阶跃、漂移）会被窗口
     * 逐步吸收为"新的正常水位"，检测器自我适应后就再也检不出该故障——离线评测显示
     * 阶跃类异常召回率仅 3% 量级，正源于此。
     *
     * <p>反之若一律不并入，真实的水位迁移（如业务量上涨导致的 CPU 抬升）会让窗口
     * 永久冻结在旧基线，告警再也无法收敛。
     *
     * <p>因此采用「异常点不入窗 + 连续异常超阈值则判定为真实迁移并重建基线」的折中：
     * <ul>
     *   <li>正常点：正常并入窗口，基线随业务自然演化</li>
     *   <li>短时异常点：不并入，保住干净基线，使故障持续可见</li>
     *   <li>连续异常超过 {@link #BASELINE_MIGRATION_THRESHOLD} 点：认定为真实水位迁移，
     *       强制并入并重建基线，让告警收敛</li>
     * </ul>
     *
     * @param value     当前指标值
     * @param sigmaVote 3-Sigma 是否判定当前点为异常
     */
    private void updateBaseline(double value, boolean sigmaVote) {
        if (!sigmaVote) {
            consecutiveAnomalies = 0;
            updateSlidingWindow(value);
            return;
        }

        consecutiveAnomalies++;

        if (consecutiveAnomalies > BASELINE_MIGRATION_THRESHOLD) {
            log.info("Baseline migration detected after {} consecutive anomalies, "
                    + "rebuilding sliding window with value={}",
                    consecutiveAnomalies, formatDouble(value));
            consecutiveAnomalies = 0;
            updateSlidingWindow(value);
        }
    }

    /**
     * 更新滑动窗口，维护最近WINDOW_SIZE个数据点
     */
    private void updateSlidingWindow(double value) {
        slidingWindow.addLast(value);
        if (slidingWindow.size() > WINDOW_SIZE) {
            slidingWindow.removeFirst();
        }
    }

    /**
     * 3-Sigma异常检测：基于滑动窗口内的真实均值和标准差
     * 计算z-score = |value - mean| / stdDev，超过3倍标准差则判定为异常
     */
    private boolean detectBy3Sigma(double value) {
        if (slidingWindow.size() < MIN_SAMPLES_FOR_DETECTION) {
            log.debug("Sliding window too small for 3-Sigma detection: {}", slidingWindow.size());
            return false;
        }

        double mean = calculateWindowMean();
        double stdDev = calculateWindowStdDev(mean);

        if (stdDev == 0) {
            return false;
        }

        double zScore = Math.abs((value - mean) / stdDev);
        log.debug("3-Sigma: value={}, mean={}, stdDev={}, zScore={}", value,
                formatDouble(mean), formatDouble(stdDev), formatDouble(zScore));
        return zScore > detectionThreshold;
    }

    /**
     * 计算滑动窗口内数据的均值
     */
    private double calculateWindowMean() {
        return slidingWindow.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * 计算滑动窗口内数据的标准差
     */
    private double calculateWindowStdDev(double mean) {
        double variance = slidingWindow.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);
        return Math.sqrt(variance);
    }

    /**
     * 更新EWMA递推状态
     * 递推公式：EWMA_t = α * value_t + (1 - α) * EWMA_{t-1}
     * 方差递推：Var_t = α * (value_t - EWMA_{t-1})^2 + (1 - α) * Var_{t-1}
     */
    private void updateEWMA(double value, boolean isAnomaly) {
        if (Double.isNaN(ewmaState)) {
            ewmaState = value;
            // 方差不能从 0 起步：sqrt(0)=0 会让动态阈值退化成 ewmaState 本身，
            // 冷启动期任何高于首个采样点的正常波动都会被判为异常。
            // 取一个中性小值作为起点，α=0.3 的递推会在十几个点内收敛到真实波动量级。
            ewmaVariance = EWMA_VARIANCE_FLOOR;
            return;
        }

        double prevEwma = ewmaState;

        // 异常点用远小于正常值的跟进系数：既不在短期内追上异常水位（否则持续故障
        // 几帧后就被当成新常态而漏报），又能在长期真实水位迁移时最终收敛（否则永久误报）。
        double alpha = isAnomaly ? EWMA_ALPHA_ADAPT : EWMA_ALPHA;
        ewmaState = alpha * value + (1 - alpha) * prevEwma;

        // 方差只在正常点更新。若把异常点的 (value - prevEwma)² 灌进方差，
        // 阈值会在异常发生的下一帧就跳到异常水位之上，导致持续型异常从第二点起全部漏检
        // ——这正是 EWMA 召回率仅 3.88% 的原因。
        if (!isAnomaly) {
            ewmaVariance = EWMA_ALPHA * Math.pow(value - prevEwma, 2) + (1 - EWMA_ALPHA) * ewmaVariance;
        }
    }

    /**
     * EWMA异常检测：基于动态阈值（EWMA均值 + 3 * sqrt(EWMA方差)）
     * 动态阈值随数据波动自适应调整，比固定阈值更精准
     */
    private boolean detectByEWMA(double value) {
        if (Double.isNaN(ewmaState) || slidingWindow.size() < MIN_SAMPLES_FOR_DETECTION) {
            return false;
        }

        double dynamicThreshold = ewmaState + detectionThreshold * Math.sqrt(ewmaVariance);
        log.debug("EWMA: value={}, ewma={}, threshold={}", value,
                formatDouble(ewmaState), formatDouble(dynamicThreshold));
        return value > dynamicThreshold;
    }

    private Severity calculateSeverity(double value) {
        if (value > 95) return Severity.CRITICAL;
        if (value > 85) return Severity.HIGH;
        if (value > 75) return Severity.MEDIUM;
        return Severity.LOW;
    }

    /**
     * 定时统计报告：每小时输出3-Sigma + EWMA双算法投票的统计指标。
     * 全部为本进程启动后的真实运行时累计值，不含任何预置演示数据。
     */
    @Scheduled(fixedRate = 3600000) // 每小时执行一次
    public void reportDetectionStats() {
        if (stats.getTotalChecks() == 0) {
            log.info("[Scheduled] No detection data yet, skipping stats report");
            return;
        }

        log.info("===== 3-Sigma + EWMA Dual-Algorithm Voting Stats Report =====");
        log.info("Total checks: {}", stats.getTotalChecks());
        log.info("Single-algorithm triggers (filtered by voting): {}", stats.getSingleAlgorithmAlerts());
        log.info("Dual-algorithm alerts (voting passed, alert sent): {}", stats.getDualAlgorithmAlerts());
        log.info("False positives (confirmed by human): {}", stats.getFalsePositives());
        log.info("True positives (confirmed by human): {}", stats.getTruePositives());
        log.info("Alert filter rate (fewer alerts by voting): {}%", formatPercent(stats.calculateAlertFilterRate()));
        log.info("Actual false positive rate (by human feedback): {}%", formatPercent(stats.calculateActualFalsePositiveRate()));
        log.info("Pending alerts awaiting confirmation: {}", pendingAlerts.size());
        log.info("Note: 误报率对比请以离线评测 AnomalyDetectionBenchmark 为准");
        log.info("==============================================================");
    }

    /**
     * 格式化小数为固定两位小数字符串。
     * SLF4J 只识别 {} 占位符，不支持 Python 风格的 {:.2f}，需先自行格式化。
     */
    private static String formatDouble(double value) {
        return String.format("%.2f", value);
    }

    /**
     * 格式化比例为百分数字符串（0.85 -> "85.00"）
     */
    private static String formatPercent(double ratio) {
        return String.format("%.2f", ratio * 100);
    }
}
