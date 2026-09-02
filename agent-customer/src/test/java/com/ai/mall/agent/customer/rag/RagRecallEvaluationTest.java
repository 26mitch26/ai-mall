package com.ai.mall.agent.customer.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 召回率评估测试（离线可复现，无外部依赖）
 * <p>
 * 运行方式：mvn -pl agent-customer test -Dtest=RagRecallEvaluationTest
 * 输出多套真实可复现数字，并分步展示优化增量：
 * V0 纯向量基线 → V1 生产混合链路 → V2 +Query改写 → V3 +Parent-Child → V4 +精排调参 → V5 +规范主题标签精确命中
 * 断言锁死两条结论：优化链路首条命中（Recall@1）显著高于基线，且 Recall@3 不退化。
 */
class RagRecallEvaluationTest {

    @Test
    void hybridRetrievalImprovesRecall() throws Exception {
        RagRecallEvaluator evaluator = new RagRecallEvaluator(KnowledgeBaseFixture.fullKnowledgeBase());
        String report = evaluator.fullReport(KnowledgeBaseFixture.queries());
        System.out.println(report);

        // 诊断：打印未进 Top1 的 query（position>0），定位 Recall@1 天花板的具体构成
        System.out.println("--- V4 未进 Top1 的 query（position 从 0 起） ---");
        evaluator.diagnoseV4(KnowledgeBaseFixture.queries()).forEach((q, pos) -> {
            if (pos > 0) {
                System.out.println("  pos=" + pos + "  " + q);
            }
        });

        // V5 = V4 + 规范主题标签精确命中置顶（仿 MiMo-Code exact 阶段）
        System.out.println("--- V5(规范主题标签精确命中) 仍不进 Top1 的 query ---");
        for (KnowledgeBaseFixture.QueryGold q : KnowledgeBaseFixture.queries()) {
            int pos = evaluator.positionOf(evaluator.canonicalPromoteRetrieve(q.query(), 5), q.goldDocId());
            if (pos > 0) {
                System.out.println("  pos=" + pos + "  " + q.query() + " ⇢ " + q.goldDocId());
            }
        }
        RagRecallEvaluator.Metrics v5 = evaluator.compute(KnowledgeBaseFixture.queries(),
                q -> evaluator.positionOf(evaluator.canonicalPromoteRetrieve(q.query(), 5), q.goldDocId()));
        System.out.printf("✓ 规范主题标签精确命中 V5  Recall@1=%.1f%% Recall@3=%.1f%%  MRR=%.3f%n",
                v5.recallAt1 * 100, v5.recallAt3 * 100, v5.mrr);

        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.write(
                java.nio.file.Path.of("target", "rag-recall-report.txt"),
                report.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // V5 = 推荐优化链路（Query改写 + 精排调参 + 规范主题标签精确命中置顶）
        RagRecallEvaluator.Metrics optimized = evaluator.evaluate(KnowledgeBaseFixture.queries());
        // V1 = 优化前的生产混合链路
        RagRecallEvaluator.Metrics production = evaluator.evaluateProduction(KnowledgeBaseFixture.queries());
        // V0 = 纯向量基线
        RagRecallEvaluator.Metrics baseline = new RagRecallEvaluator(KnowledgeBaseFixture.fullKnowledgeBase())
                .evaluateBaseline(KnowledgeBaseFixture.queries());

        // 结论 1：优化链路首条命中显著优于纯向量基线
        assertTrue(optimized.recallAt1 > baseline.recallAt1,
                "优化链路 Recall@1 应显著高于纯向量基线");
        // 结论 2：优化链路相对优化前的生产混合链路不退化（含首条）
        assertTrue(optimized.recallAt1 >= production.recallAt1,
                "优化链路 Recall@1 应 >= 优化前生产混合链路");
        assertTrue(optimized.recallAt3 >= production.recallAt3,
                "优化链路 Recall@3 应 >= 优化前生产混合链路");

        System.out.printf("✓ Recall@1 优化前 %.1f%% → 优化后 %.1f%%（基线 %.1f%%）%n",
                production.recallAt1 * 100, optimized.recallAt1 * 100, baseline.recallAt1 * 100);
        System.out.printf("✓ Recall@3 优化前 %.1f%% → 优化后 %.1f%%%n",
                production.recallAt3 * 100, optimized.recallAt3 * 100);
    }
}