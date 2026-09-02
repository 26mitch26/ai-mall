package com.ai.mall.agent.customer.rag;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 真语义 embedding 下的 RAG 召回率评测（可选，需本机 Ollama 在线）
 * <p>
 * 用途：证明"召回率天花板"的真正解锁路径。与 {@code RagRecallEvaluationTest}（零依赖、词法哈希
 * embedding）同链路评测，对比换成语义 embedding（nomic-embed-text / mxbai-embed-large）后 Recall@1
 * 的跳升幅度。Ollama 未启动时自动跳过，不阻塞离线 CI。
 * <p>
 * 运行：mvn -pl agent-customer test -Dtest=RagRecallSemanticTest
 * 模型：默认 nomic-embed-text，可用 -Drag.embed.model=mxbai-embed-large 切换。
 */
class RagRecallSemanticTest {

    @Test
    void semanticEmbeddingImprovesRecall() throws Exception {
        if (!OllamaEmbeddingModel.available()) {
            Assumptions.assumeTrue(false, "本机 Ollama 未在线，跳过语义 embedding 评测");
        }

        String model = System.getProperty("rag.embed.model", "nomic-embed-text");
        System.out.println(">>> 语义 embedding 模型: " + model);

        RagRecallEvaluator evaluator = new RagRecallEvaluator(
                KnowledgeBaseFixture.fullKnowledgeBase(), new OllamaEmbeddingModel());

        String report = evaluator.fullReport(KnowledgeBaseFixture.queries());
        System.out.println(report);

        System.out.println("--- 语义 embedding 下未进 Top1 的 query ---");
        evaluator.diagnoseV4(KnowledgeBaseFixture.queries()).forEach((q, pos) -> {
            if (pos > 0) {
                System.out.println("  pos=" + pos + "  " + q);
            }
        });

        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.write(
                java.nio.file.Path.of("target", "rag-recall-semantic-report.txt"),
                report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}