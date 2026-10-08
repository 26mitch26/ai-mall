package com.ai.mall.agent.customer.service.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 闲聊意图的语义路由（正则之后的第二层）：把查询向量与各组意图范例向量做余弦比对，
 * 泛化正则枚举不到的表述（"你是机器人么""可以和我聊天吗"一类）。
 *
 * 设计取舍（与 GreetingIntent 构成级联）：
 * - 正则层先行：高精度零成本，命中即短路；
 * - 语义层兜底：只覆盖"无业务信息需求"的闲聊意图（寒暄/身份/陪聊），阈值 0.80（bge-m3 实测：
 *   各意图正例 ≥0.87，业务问句 ≤0.67，前缀寒暄+业务 0.69~0.70，两侧各留约 0.1 边距）；
 * - 业务词守卫：含业务关键词的查询直接放行业务链路，不做语义判定（防误路由 + 省一次向量调用）；
 * - 失败降级：embedding 不可用时返回 null 走原流程，绝不因意图路由阻断对话。
 * 范例集来自真实用户问句并持续迭代；漏检时补范例，不降阈值（避免挤压业务侧安全边距）。
 */
@Slf4j
@Service
public class SemanticIntentRouter {

    /** 业务词守卫复用 GreetingIntent.BUSINESS_HINT（闲聊兜底分层判定同一份定义） */


    private final EmbeddingModel embeddingModel;

    @Value("${agent.intent.casual.threshold:0.80}")
    private double threshold = 0.80;

    private final List<IntentGroup> groups;

    public SemanticIntentRouter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.groups = List.of(
                new IntentGroup(GreetingIntent.ANSWER, List.of(
                        "你好", "您好", "在吗", "嗨", "hello", "hi", "早上好", "晚上好", "谢谢", "多谢")),
                new IntentGroup(GreetingIntent.IDENTITY_ANSWER, List.of(
                        "你是谁", "你叫什么名字", "自我介绍一下", "你是机器人吗", "你是真人吗",
                        "你能做什么", "你会什么", "介绍一下你的功能",
                        "你能帮我做什么", "你可以帮我做些什么", "你都会些什么",
                        "你认识我吗")),
                new IntentGroup(GreetingIntent.CHAT_ANSWER, List.of(
                        "可以和我聊天吗", "你能陪我聊天吗", "陪我聊聊天", "和我说说话", "我们聊会儿天吧", "好无聊啊",
                        "我想和你聊天")));
    }

    /**
     * 语义判定闲聊意图。命中返回对应意图的固定回复，未命中/被守卫拦截/失败返回 null。
     */
    public String tryMatch(String query) {
        if (query == null || query.isBlank()) return null;
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        // 业务守卫：绝大多数业务查询在正则层之后就放行，不付出向量调用成本
        if (GreetingIntent.isBusinessQuery(normalized)) return null;

        try {
            float[] queryVector = embeddingModel.embed(normalized);
            String bestAnswer = null;
            double bestSim = 0.0;
            for (IntentGroup group : groups) {
                double sim = bestSimilarity(queryVector, group.vectors());
                if (sim > bestSim) {
                    bestSim = sim;
                    bestAnswer = group.answer;
                }
            }
            boolean hit = bestSim >= threshold;
            log.info("闲聊语义路由: query='{}', best={}, threshold={}, hit={}",
                    query, String.format("%.3f", bestSim), threshold, hit);
            return hit ? bestAnswer : null;
        } catch (Exception e) {
            log.warn("闲聊语义路由不可用，降级走原流程: {}", e.getMessage());
            return null;
        }
    }

    private double bestSimilarity(float[] queryVector, float[][] exemplarVectors) {
        double best = 0.0;
        for (float[] exemplar : exemplarVectors) best = Math.max(best, cosine(queryVector, exemplar));
        return best;
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 闲聊意图组：范例集 + 命中后的固定回复；范例向量首次使用时构建并缓存 */
    private final class IntentGroup {
        private final String answer;
        private final List<String> exemplars;
        private final AtomicReference<float[][]> vectorCache = new AtomicReference<>();

        private IntentGroup(String answer, List<String> exemplars) {
            this.answer = answer;
            this.exemplars = exemplars;
        }

        private float[][] vectors() {
            float[][] existing = vectorCache.get();
            if (existing != null) return existing;
            synchronized (SemanticIntentRouter.this) {
                if (vectorCache.get() == null) {
                    List<float[]> embedded = embeddingModel.embed(exemplars);
                    vectorCache.set(embedded.toArray(float[][]::new));
                }
                return vectorCache.get();
            }
        }
    }
}
