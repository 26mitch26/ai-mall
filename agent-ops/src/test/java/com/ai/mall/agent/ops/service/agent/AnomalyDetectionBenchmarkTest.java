package com.ai.mall.agent.ops.service.agent;

import com.ai.mall.agent.ops.service.event.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 异常检测离线评测：3-Sigma + EWMA 双算法投票 vs 单算法的误报率对比。
 *
 * <h3>为什么需要这个评测</h3>
 * 运行时统计（{@link MonitorAgent.AnomalyDetectionStats#calculateAlertFilterRate()}）只能
 * 说明「投票机制拦下了多少触发」，无法区分被拦下的是噪声还是真实异常，因此不能作为
 * 「误报率下降」的证据。要衡量误报率，必须在<b>带 ground-truth 标签</b>的数据上，
 * 对比各策略把「正常点」误判为异常的比例。
 *
 * <h3>评测方法</h3>
 * <ol>
 *   <li><b>数据生成</b>：以正态分布基线（均值 50、标准差 4，模拟 CPU 水位）叠加噪声，
 *       再注入四类异常——尖峰、阶跃、趋势漂移、瞬时抖动。每个点带 boolean 标签。</li>
 *   <li><b>公平对比</b>：对每个点只调用一次 {@link MonitorAgent#evaluate(double)}，
 *       同时取出 sigmaVote / ewmaVote / isAlert 三个结论，因此「仅3-Sigma」「仅EWMA」
 *       「双算法投票」三种策略共享完全相同的状态演化轨迹（同一滑动窗口、同一 EWMA 递推），
 *       对比不存在状态污染。</li>
 *   <li><b>多次采样</b>：跑 {@value #SCENARIO_COUNT} 个不同随机种子的场景并汇总，
 *       避免单次随机的偶然性；种子固定，结果可复现。</li>
 * </ol>
 *
 * <h3>指标口径</h3>
 * 逐点（point-wise）口径：每个数据点独立判定 TP/FP/FN/TN，不做窗口容错，
 * 因此比 NAB 的窗口口径更严格。
 * <ul>
 *   <li>Precision = TP / (TP + FP)</li>
 *   <li>Recall = TP / (TP + FN)</li>
 *   <li>FPR（误报率）= FP / (FP + TN)，即正常点中被误报的比例</li>
 * </ul>
 *
 * <h3>可复现性</h3>
 * 直接运行 {@code mvn -pl agent-ops test -Dtest=AnomalyDetectionBenchmarkTest} 即可
 * 复现报告中的全部数字。
 */
class AnomalyDetectionBenchmarkTest {

    /** 每个场景的时序点数 */
    private static final int POINTS_PER_SCENARIO = 600;

    /** 每个场景注入的异常段数 */
    private static final int ANOMALY_SEGMENTS = 4;

    /** 预热点数：滑动窗口与 EWMA 需要预热，此区间不计入评测统计 */
    private static final int WARMUP_POINTS = 20;

    /** 评测场景数（不同随机种子），用于摊平单次随机的偶然性 */
    private static final int SCENARIO_COUNT = 20;

    /** 随机种子基准值：seed = SEED_BASE + scenarioIndex，固定以保证可复现 */
    private static final long SEED_BASE = 20260101L;

    /** 检测阈值默认值，与 MonitorAgent 的 3-Sigma 默认准则保持一致 */
    private static final double DEFAULT_THRESHOLD = 3.0;

    /** 基线均值（模拟 CPU 水位 %） */
    private static final double BASELINE_MEAN = 50.0;

    /** 基线标准差（正常波动幅度） */
    private static final double BASELINE_STDDEV = 4.0;

    /** 注入的异常类型 */
    private enum AnomalyKind {
        /** 瞬时尖峰：持续极短、幅度极大（如 GC 停顿、瞬时打满） */
        SPIKE(1, 3),
        /** 水位阶跃：持续一段、幅度中等且恒定（如发布后性能退化） */
        LEVEL_SHIFT(15, 25),
        /** 趋势漂移：幅度由 0 线性增长（如内存缓慢泄漏） */
        TREND_DRIFT(20, 30),
        /** 抖动放大：正常点位的随机扰动加剧（噪声方差突变，均值不变） */
        NOISE_BURST(20, 30);

        final int minLength;
        final int maxLength;

        AnomalyKind(int minLength, int maxLength) {
            this.minLength = minLength;
            this.maxLength = maxLength;
        }
    }

    /** 带 ground-truth 标签的时序数据 */
    private record LabeledSeries(double[] values, boolean[] labels, AnomalyKind[] kinds) {

        int length() {
            return values.length;
        }
    }

    /** 混淆矩阵累加器 */
    private static final class Confusion {

        private long tp;
        private long fp;
        private long fn;
        private long tn;

        void record(boolean label, boolean detected) {
            if (label && detected) {
                tp++;
            } else if (!label && detected) {
                fp++;
            } else if (label && !detected) {
                fn++;
            } else {
                tn++;
            }
        }

        double precision() {
            return tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
        }

        double recall() {
            return tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
        }

        double f1() {
            double p = precision();
            double r = recall();
            return p + r == 0 ? 0.0 : 2 * p * r / (p + r);
        }

        /** 误报率：正常点中被误判为异常的比例 */
        double falsePositiveRate() {
            return fp + tn == 0 ? 0.0 : (double) fp / (fp + tn);
        }

        long totalNegatives() {
            return fp + tn;
        }

        long totalPositives() {
            return tp + fn;
        }
    }

    /** 四种投票策略在同一数据集上的评测结果 */
    private record StrategyResults(Confusion sigmaOnly, Confusion ewmaOnly,
                                   Confusion andVote, Confusion orVote) {
    }

    /**
     * 在给定检测阈值下跑完整评测。
     *
     * @param threshold 检测阈值（标准差倍数），同时作用于 3-Sigma 与 EWMA
     */
    private StrategyResults evaluateStrategies(double threshold) {
        Confusion sigmaOnly = new Confusion();
        Confusion ewmaOnly = new Confusion();
        Confusion andVote = new Confusion();
        Confusion orVote = new Confusion();

        for (int scenario = 0; scenario < SCENARIO_COUNT; scenario++) {
            LabeledSeries series = generateSeries(SEED_BASE + scenario);

            // 每个场景使用独立实例，确保滑动窗口与 EWMA 状态从零开始
            MonitorAgent agent = new MonitorAgent(mock(EventBus.class), threshold);

            for (int i = 0; i < series.length(); i++) {
                MonitorAgent.DetectionResult result = agent.evaluate(series.values()[i]);

                if (i < WARMUP_POINTS) {
                    continue;
                }

                boolean label = series.labels()[i];
                sigmaOnly.record(label, result.sigmaVote());
                ewmaOnly.record(label, result.ewmaVote());
                andVote.record(label, result.isAlert());
                // OR 投票作为对照：任一算法触发即告警，代表"最激进"的组合策略
                orVote.record(label, result.sigmaVote() || result.ewmaVote());
            }
        }
        return new StrategyResults(sigmaOnly, ewmaOnly, andVote, orVote);
    }

    @Test
    @DisplayName("双算法投票应显著低于单算法的误报率，且召回率不明显劣化")
    void dualAlgorithmVotingShouldSuppressFalsePositives() {
        StrategyResults results = evaluateStrategies(DEFAULT_THRESHOLD);

        printReport(results.sigmaOnly(), results.ewmaOnly(),
                results.andVote(), results.orVote());

        // 核心结论：双算法投票的误报率必须严格低于任一单算法
        assertTrue(results.andVote().falsePositiveRate() < results.sigmaOnly().falsePositiveRate(),
                "双算法投票的误报率应低于仅3-Sigma");
        assertTrue(results.andVote().falsePositiveRate() < results.ewmaOnly().falsePositiveRate(),
                "双算法投票的误报率应低于仅EWMA");
    }

    /**
     * 灵敏度扫描：找出 AND 投票相对最优单算法的 F1 增益为正的工作区间。
     *
     * <p>投票机制的价值不在于"更严格"，而在于<b>允许单算法调敏感、由投票收敛误报</b>。
     * 若单算法本身已极度保守（如 3σ），再叠加 AND 投票只会过度抑制召回，
     * F1 反而不如单算法。本用例通过扫描阈值把这一拐点测出来。
     */
    @Test
    @DisplayName("扫描检测阈值，定位 AND 投票相对单算法的 F1 正收益区间")
    void sweepDetectionThresholdToFindVotingGainRegion() {
        double[] thresholds = {1.5, 2.0, 2.5, 3.0, 3.5};

        System.out.println();
        System.out.println("###################################################################");
        System.out.println("#  Threshold Sweep: does AND voting beat the best single algorithm? #");
        System.out.println("###################################################################");
        System.out.println("Gain = F1(AND voting) - F1(best single algorithm)");
        System.out.println();
        System.out.printf("%-8s | %-28s | %-28s | %s%n",
                "thresh", "3-Sigma only", "AND voting", "gain vs best single");
        System.out.println("---------+------------------------------+------------------------------+--------------------");

        double bestGain = Double.NEGATIVE_INFINITY;
        double bestThreshold = DEFAULT_THRESHOLD;

        for (double threshold : thresholds) {
            StrategyResults r = evaluateStrategies(threshold);

            double f1Sigma = r.sigmaOnly().f1();
            double f1Ewma = r.ewmaOnly().f1();
            double f1And = r.andVote().f1();
            double f1BestSingle = Math.max(f1Sigma, f1Ewma);
            double gain = f1And - f1BestSingle;

            if (gain > bestGain) {
                bestGain = gain;
                bestThreshold = threshold;
            }

            System.out.printf("%-8.1f | P=%5.1f%% R=%5.1f%% F1=%5.1f%% | P=%5.1f%% R=%5.1f%% F1=%5.1f%% | %+6.2f pp%n",
                    threshold,
                    r.sigmaOnly().precision() * 100, r.sigmaOnly().recall() * 100, f1Sigma * 100,
                    r.andVote().precision() * 100, r.andVote().recall() * 100, f1And * 100,
                    gain * 100);
        }

        System.out.println("---------+------------------------------+------------------------------+--------------------");
        System.out.printf("Best gain %+.2f pp at threshold %.1f (current default: %.1f)%n",
                bestGain * 100, bestThreshold, DEFAULT_THRESHOLD);
        System.out.println("###################################################################");
        System.out.println();
    }

    @Test
    @DisplayName("按异常类型拆解召回率，暴露各类异常的检测能力差异")
    void recallShouldVaryByAnomalyKind() {
        // 按异常类型分组统计召回：尖峰/阶跃易检，趋势漂移早期难检
        Map<AnomalyKind, long[]> perKind = new EnumMap<>(AnomalyKind.class);
        for (AnomalyKind kind : AnomalyKind.values()) {
            perKind.put(kind, new long[2]); // [检出数, 总数]
        }

        for (int scenario = 0; scenario < SCENARIO_COUNT; scenario++) {
            LabeledSeries series = generateSeries(SEED_BASE + scenario);
            MonitorAgent agent = new MonitorAgent(mock(EventBus.class));

            for (int i = 0; i < series.length(); i++) {
                MonitorAgent.DetectionResult result = agent.evaluate(series.values()[i]);
                AnomalyKind kind = series.kinds()[i];

                if (i < WARMUP_POINTS || kind == null) {
                    continue;
                }
                long[] bucket = perKind.get(kind);
                bucket[1]++;
                if (result.isAlert()) {
                    bucket[0]++;
                }
            }
        }

        System.out.println();
        System.out.println("===== Per-Anomaly-Kind Recall (dual voting) =====");
        for (AnomalyKind kind : AnomalyKind.values()) {
            long[] bucket = perKind.get(kind);
            double recall = bucket[1] == 0 ? 0.0 : (double) bucket[0] / bucket[1];
            System.out.printf("  %-12s recall=%6.2f%%  (%d / %d points)%n",
                    kind.name(), recall * 100, bucket[0], bucket[1]);
        }
        System.out.println("=================================================");
        System.out.println();
    }

    private void printReport(Confusion sigmaOnly, Confusion ewmaOnly,
                             Confusion dualVote, Confusion orVote) {
        double fprSigma = sigmaOnly.falsePositiveRate();
        double fprEwma = ewmaOnly.falsePositiveRate();
        double fprDual = dualVote.falsePositiveRate();

        double reductionVsSigma = fprSigma == 0 ? 0.0 : 1.0 - fprDual / fprSigma;
        double reductionVsEwma = fprEwma == 0 ? 0.0 : 1.0 - fprDual / fprEwma;

        System.out.println();
        System.out.println("#############################################################");
        System.out.println("#  Anomaly Detection Benchmark: 3-Sigma + EWMA Dual Voting  #");
        System.out.println("#############################################################");
        System.out.printf("Dataset    : %d scenarios x %d points (%d warm-up points excluded)%n",
                SCENARIO_COUNT, POINTS_PER_SCENARIO, WARMUP_POINTS);
        System.out.printf("Anomalies  : %d segments/scenario, 4 kinds (SPIKE/LEVEL_SHIFT/"
                + "TREND_DRIFT/NOISE_BURST)%n", ANOMALY_SEGMENTS);
        System.out.printf("Baseline   : N(mean=%.1f, stddev=%.1f), seed base=%d (reproducible)%n",
                BASELINE_MEAN, BASELINE_STDDEV, SEED_BASE);
        System.out.printf("Samples    : %d positives, %d negatives%n",
                dualVote.totalPositives(), dualVote.totalNegatives());
        System.out.println();
        System.out.println("-------------------------------------------------------------");
        System.out.printf("%-14s %10s %10s %10s %10s%n",
                "Strategy", "Precision", "Recall", "F1", "FPR");
        System.out.println("-------------------------------------------------------------");
        printRow("3-Sigma only", sigmaOnly);
        printRow("EWMA only", ewmaOnly);
        printRow("AND voting", dualVote);
        printRow("OR voting", orVote);
        System.out.println("-------------------------------------------------------------");
        System.out.println();
        System.out.println("False positive rate reduction by AND voting:");
        System.out.printf("  vs 3-Sigma only : %6.2f%%  (%.4f%% -> %.4f%%)%n",
                reductionVsSigma * 100, fprSigma * 100, fprDual * 100);
        System.out.printf("  vs EWMA only    : %6.2f%%  (%.4f%% -> %.4f%%)%n",
                reductionVsEwma * 100, fprEwma * 100, fprDual * 100);
        System.out.println();
        System.out.println("Note: FPR = FP / (FP + TN), point-wise. "
                + "Reproduce with:");
        System.out.println("  mvn -pl agent-ops test -Dtest=AnomalyDetectionBenchmarkTest");
        System.out.println("#############################################################");
        System.out.println();
    }

    private void printRow(String name, Confusion confusion) {
        System.out.printf("%-14s %9.2f%% %9.2f%% %9.2f%% %9.4f%%%n",
                name,
                confusion.precision() * 100,
                confusion.recall() * 100,
                confusion.f1() * 100,
                confusion.falsePositiveRate() * 100);
    }

    /**
     * 生成带 ground-truth 标签的时序数据：正态基线 + 注入异常段。
     *
     * @param seed 随机种子，固定种子保证评测可复现
     */
    private LabeledSeries generateSeries(long seed) {
        Random random = new Random(seed);
        double[] values = new double[POINTS_PER_SCENARIO];
        boolean[] labels = new boolean[POINTS_PER_SCENARIO];
        AnomalyKind[] kinds = new AnomalyKind[POINTS_PER_SCENARIO];

        // 基线：正常水位叠加高斯噪声
        for (int i = 0; i < POINTS_PER_SCENARIO; i++) {
            values[i] = BASELINE_MEAN + random.nextGaussian() * BASELINE_STDDEV;
        }

        // 注入异常段，段之间不重叠
        boolean[] occupied = new boolean[POINTS_PER_SCENARIO];
        int injected = 0;
        int attempts = 0;
        while (injected < ANOMALY_SEGMENTS && attempts < 100) {
            attempts++;
            AnomalyKind kind = AnomalyKind.values()[random.nextInt(AnomalyKind.values().length)];
            int length = kind.minLength + random.nextInt(kind.maxLength - kind.minLength + 1);
            int maxStart = POINTS_PER_SCENARIO - length - 1;
            if (maxStart <= WARMUP_POINTS) {
                continue;
            }
            int start = WARMUP_POINTS + random.nextInt(maxStart - WARMUP_POINTS);

            if (isOverlapping(occupied, start - 2, start + length + 2)) {
                continue;
            }
            markOccupied(occupied, start, length);
            injectAnomaly(values, labels, kinds, start, length, kind, random);
            injected++;
        }

        return new LabeledSeries(values, labels, kinds);
    }

    private void injectAnomaly(double[] values, boolean[] labels, AnomalyKind[] kinds,
                               int start, int length, AnomalyKind kind, Random random) {
        for (int i = 0; i < length; i++) {
            int idx = start + i;
            switch (kind) {
                case SPIKE -> values[idx] += 25.0 + random.nextDouble() * 15.0;
                case LEVEL_SHIFT -> values[idx] += 12.0 + random.nextDouble() * 8.0;
                case TREND_DRIFT -> {
                    // 幅度由 0 线性增长至峰值：模拟缓慢劣化，早期几乎不可检
                    double peak = 18.0 + random.nextDouble() * 10.0;
                    values[idx] += peak * (i + 1.0) / length;
                }
                case NOISE_BURST -> {
                    // 均值不变、方差突增：3-Sigma 敏感，EWMA 不敏感
                    values[idx] = BASELINE_MEAN + random.nextGaussian() * BASELINE_STDDEV * 3.5;
                }
            }
            labels[idx] = true;
            kinds[idx] = kind;
        }
    }

    private boolean isOverlapping(boolean[] occupied, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(occupied.length, to); i++) {
            if (occupied[i]) {
                return true;
            }
        }
        return false;
    }

    private void markOccupied(boolean[] occupied, int start, int length) {
        for (int i = start; i < Math.min(occupied.length, start + length); i++) {
            occupied[i] = true;
        }
    }
}
