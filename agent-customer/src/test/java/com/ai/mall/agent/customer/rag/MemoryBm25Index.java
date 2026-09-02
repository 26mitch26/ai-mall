package com.ai.mall.agent.customer.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存版 BM25 索引（RAG 离线评测专用）
 * <p>
 * 生产环境（RagService.bm25KeywordRetrieve）基于 Redis 倒排索引实现 BM25；
 * 本类用内存结构实现**完全相同的公式与参数**（k1=1.5, b=0.75），
 * 使评测不需要 Redis/容器即可复现关键词召回这一路。
 * <p>
 * 公式：score(D,Q) = Σ IDF(qi) × (f(qi,D) × (k1+1)) / (f(qi,D) + k1×(1 - b + b×|D|/avgdl))
 * IDF(qi) = ln((N - df + 0.5) / (df + 0.5))
 */
public final class MemoryBm25Index {

    /** 与生产一致：BM25 k1（词频饱和度） */
    private static final double K1 = 1.5;
    /** 与生产一致：BM25 b（文档长度归一化） */
    private static final double B = 0.75;

    /** 文档条目 */
    public static final class Entry {
        public final String id;
        public final String text;
        public final List<String> tokens;

        Entry(String id, String text, List<String> tokens) {
            this.id = id;
            this.text = text;
            this.tokens = tokens;
        }
    }

    private final List<Entry> docs = new ArrayList<>();
    private final Map<String, Set<String>> inverted = new HashMap<>();
    private final Map<String, Map<String, Integer>> termFreq = new HashMap<>();
    private double totalLength = 0.0;

    /** 加入一篇文档（生产中等价于写入 Redis 倒排索引 + 词频 Hash） */
    public void add(String id, String text) {
        List<String> tokens = RagTokenizer.tokenize(text);
        docs.add(new Entry(id, text, tokens));
        termFreq.put(id, countFreq(tokens));

        Set<String> unique = new HashSet<>(tokens);
        for (String term : unique) {
            inverted.computeIfAbsent(term, k -> new HashSet<>()).add(id);
        }
        totalLength += tokens.size();
    }

    /** BM25 检索：返回 (docId, score) 有序列表（降序） */
    public List<Map.Entry<String, Double>> search(String query, int topK) {
        List<String> queryTerms = RagTokenizer.tokenize(query);
        if (queryTerms.isEmpty() || docs.isEmpty()) {
            return Collections.emptyList();
        }
        int totalDocs = docs.size();
        double avgDocLen = totalLength / totalDocs;

        Map<String, Double> scores = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (String term : queryTerms) {
            if (seen.contains(term)) {
                continue;
            }
            seen.add(term);
            Set<String> matchingDocs = inverted.get(term);
            if (matchingDocs == null || matchingDocs.isEmpty()) {
                continue;
            }
            int df = matchingDocs.size();
            double idf = Math.log((totalDocs - df + 0.5) / (df + 0.5));
            for (String docId : matchingDocs) {
                Map<String, Integer> tfMap = termFreq.get(docId);
                int tf = tfMap == null ? 0 : tfMap.getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                int docLen = docs.get(indexOf(docId)).tokens.size();
                double tfNorm = (tf * (K1 + 1.0))
                        / (tf + K1 * (1.0 - B + B * docLen / avgDocLen));
                scores.merge(docId, idf * tfNorm, Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .toList();
    }

    private int indexOf(String id) {
        for (int i = 0; i < docs.size(); i++) {
            if (docs.get(i).id.equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException("doc not found: " + id);
    }

    private Map<String, Integer> countFreq(List<String> tokens) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String t : tokens) {
            freq.merge(t, 1, Integer::sum);
        }
        return freq;
    }

    public int size() {
        return docs.size();
    }
}