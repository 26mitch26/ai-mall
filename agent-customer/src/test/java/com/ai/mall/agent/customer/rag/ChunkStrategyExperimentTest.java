package com.ai.mall.agent.customer.rag;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chunk 尺寸/策略对比实验（离线可复现，零外部依赖）
 * <p>
 * 用同一套评测集（24 问 / 16 篇含干扰文档）对比不同分块尺寸与切分策略下的
 * 混合检索召回指标，回答"Chunk 过大/过小分别有什么代价、本项目为什么选句边界 ≤768（512 目标）"。
 * <p>
 * 运行：mvn -pl agent-customer test -Dtest=ChunkStrategyExperimentTest -Djacoco.skip=true
 * 产物：agent-customer/target/chunk-experiment-report.txt
 */
class ChunkStrategyExperimentTest {

    private static final int[] SIZES = {128, 256, 512};
    private static final String[] STRATEGIES = {"sentence", "fixed_size"};

    @Test
    void compareChunkConfigurations() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("===========================================================\n");
        report.append(" Chunk 分块对比实验报告（24 问 / 16 篇文档 / 词法 embedding 离线评测）\n");
        report.append("===========================================================\n");
        report.append(String.format("%-26s %-9s %-10s %-10s %-10s %-10s%n",
                "配置", "分块数", "Recall@1", "Recall@3", "Recall@5", "MRR"));
        report.append("-----------------------------------------------------------\n");

        for (String strategy : STRATEGIES) {
            for (int size : SIZES) {
                RagRecallEvaluator evaluator = new RagRecallEvaluator(
                        KnowledgeBaseFixture.fullKnowledgeBase(), new LocalEmbeddingModel(), size, strategy);
                RagRecallEvaluator.Metrics v1 = evaluator.evaluateProduction(KnowledgeBaseFixture.queries());
                report.append(String.format("%-26s %-9d %-10s %-10s %-10s %-10s%n",
                        strategy + " @ " + size, evaluator.chunkCount(),
                        pct(v1.recallAt1), pct(v1.recallAt3), pct(v1.recallAt5), String.format("%.3f", v1.mrr)));
            }
        }
        report.append("===========================================================\n");
        System.out.println(report);
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "chunk-experiment-report.txt"),
                report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String pct(double value) {
        return String.format("%.1f%%", value * 100);
    }
}