package com.ai.mall.agent.customer.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地可复现的 Embedding 模型（RAG 离线评测专用）
 * <p>
 * 用途：本机没有外部模型 API Key 时，为 RAG 全链路（分块→向量检索→融合→重排）
 * 提供确定性的向量编码，使"召回率"成为可离线复现的真实数字。
 * <p>
 * 原理：字符 n-gram 哈希散列
 * - 中文按 bigram、英文按单词切分（与生产 RagService.tokenize 保持一致）
 * - 每个 n-gram 用 String.hashCode 散列到 4096 维空间，带符号权重（奇偶决定 +1/-1）降低冲突偏差
 * - L2 归一化后作为向量返回，余弦相似度可直接比较
 * <p>
 * 特性：
 * - 确定性：同一文本任意次编码结果完全一致（String.hashCode 为规范实现，跨 JVM 稳定）
 * - 零依赖 / 零网络：不回源外部模型，CI 与面试复现零成本
 * - 可插拔：实现 Spring AI 标准 EmbeddingModel 接口，换真实模型只需替换 Bean
 */
public final class LocalEmbeddingModel implements EmbeddingModel {

    /** 向量维度 */
    private static final int DIM = 4096;

    @Override
    public float[] embed(String text) {
        return encode(text);
    }

    @Override
    public float[] embed(Document document) {
        return encode(document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<Embedding> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            results.add(new Embedding(encode(texts.get(i)), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public int dimensions() {
        return DIM;
    }

    /** n-gram 哈希散列 + L2 归一化 */
    private float[] encode(String text) {
        double[] vec = new double[DIM];
        for (String token : tokenize(text)) {
            int hash = token.hashCode();
            int idx = (hash & 0x7fffffff) % DIM;
            // 符号权重：奇偶位决定贡献正负，减少哈希碰撞导致的方向偏差
            vec[idx] += (hash & 1) == 0 ? 1.0 : -1.0;
        }
        return normalize(vec);
    }

    private float[] normalize(double[] vec) {
        double norm = 0.0;
        for (double v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        float[] out = new float[DIM];
        if (norm == 0.0) {
            return out; // 空文本：全零向量
        }
        for (int i = 0; i < DIM; i++) {
            out[i] = (float) (vec[i] / norm);
        }
        return out;
    }

    /**
     * 与生产 RagService.tokenize 一致：英文单词（长度>=2）+ 中文 bigram
     */
    private List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String[] words = text.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5\\s]", " ")
                .split("\\s+");
        for (String word : words) {
            if (word.length() >= 2) {
                terms.add(word);
            }
        }
        String chinese = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < chinese.length() - 1; i++) {
            terms.add(chinese.substring(i, i + 2));
        }
        return terms;
    }
}