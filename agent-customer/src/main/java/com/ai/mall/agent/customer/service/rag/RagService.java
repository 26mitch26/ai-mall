package com.ai.mall.agent.customer.service.rag;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.security.InputSanitizer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final OpenAiChatModel mimoChatModel;
    private final InputSanitizer inputSanitizer;
    private final VectorStore vectorStore;
    private final StringRedisTemplate redisTemplate;
    private final org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    /**
     * 知识库无命中时的拒答话术
     * <p>
     * 相比"把空上下文丢给模型让它自由发挥"，直接拒答可以彻底掐断幻觉来源：
     * 检索为空意味着本次回答没有任何事实支撑，此时模型输出的任何内容都不可信。
     * 与 {@link com.ai.mall.agent.customer.service.security.OutputGuardrail} 的
     * UNSOURCED_FACT 规则配合，形成"生成前拒答 + 生成后拦截"的双保险。
     */
    public static final String NO_CONTEXT_ANSWER =
            "抱歉，我在知识库中没有找到相关的资料，无法给您准确的答复，已为您转接人工客服。";

    /** RRF融合常数，标准值k=60 */
    private static final int RRF_K = 60;
    /** BM25参数k1，控制词频饱和度 */
    private static final double BM25_K1 = 1.5;
    /** BM25参数b，控制文档长度归一化 */
    private static final double BM25_B = 0.75;

    // ==================== 内部数据结构 ====================

    /** 检索结果包装，携带排名和来源信息 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievedDocument {
        private String id;
        private String content;
        private String source;
        private String type;
        private double score;
        /** 检索来源：milvus / bm25 */
        private String retrievalSource;
        /** 在各自检索结果中的排名（从1开始） */
        private int rank;
    }

    /** RRF融合后的文档 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FusedDocument {
        private String id;
        private String content;
        private String source;
        private String type;
        /** RRF融合分数 */
        private double rrfScore;
    }

    /** 文档分块，携带元信息用于溯源和上下文拼接 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentChunk {
        /** 分块唯一ID，格式: {docId}_chunk_{index} */
        private String chunkId;
        /** 所属原始文档ID */
        private String docId;
        /** 分块文本内容 */
        private String content;
        /** 原始文档来源 */
        private String source;
        /** 原始文档类型 */
        private String type;
        /** 分块在原文中的序号（从0开始） */
        private int chunkIndex;
        /** 分块策略：fixed_size / sentence / semantic */
        private String chunkStrategy;
        /** 前一个分块的chunkId，用于上下文拼接 */
        private String prevChunkId;
        /** 后一个分块的chunkId，用于上下文拼接 */
        private String nextChunkId;
    }

    /** 重排序后的文档，携带精排分数 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RerankedDocument {
        private String id;
        private String content;
        private String source;
        private String type;
        /** RRF融合分数 */
        private double rrfScore;
        /** 重排序相关性分数（0~1，越高越相关） */
        private double rerankScore;
    }

    // ==================== Milvus向量检索 ====================

    /**
     * 基于Milvus的向量语义检索
     * <p>
     * 使用Spring AI VectorStore接口（底层为MilvusVectorStore），
     * 将query通过EmbeddingModel转为向量后进行相似度搜索，
     * 返回语义最相关的topK个文档。
     */
    public List<RetrievedDocument> milvusVectorRetrieve(String query, int topK) {
        log.info("Milvus向量检索开始, query={}, topK={}", query, topK);
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build();

            List<org.springframework.ai.document.Document> aiDocs = vectorStore.similaritySearch(searchRequest);

            List<RetrievedDocument> results = new ArrayList<>();
            for (int i = 0; i < aiDocs.size(); i++) {
                org.springframework.ai.document.Document aiDoc = aiDocs.get(i);
                results.add(RetrievedDocument.builder()
                        .id(aiDoc.getId())
                        .content(aiDoc.getText())
                        .source(String.valueOf(aiDoc.getMetadata().getOrDefault("source", "unknown")))
                        .type(String.valueOf(aiDoc.getMetadata().getOrDefault("type", "unknown")))
                        .score(extractSimilarityScore(aiDoc, i))
                        .retrievalSource("milvus")
                        .rank(i + 1)
                        .build());
            }
            log.info("Milvus向量检索完成, 返回{}条结果", results.size());
            return results;
        } catch (Exception e) {
            log.error("Milvus向量检索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 从Spring AI Document的metadata中提取相似度分数
     * Milvus返回distance，需转换为similarity（1 - distance）
     */
    private double extractSimilarityScore(org.springframework.ai.document.Document aiDoc, int fallbackRank) {
        Map<String, Object> metadata = aiDoc.getMetadata();
        if (metadata.containsKey("distance")) {
            return 1.0 - ((Number) metadata.get("distance")).doubleValue();
        }
        return 1.0 / (fallbackRank + 1);
    }

    // ==================== BM25关键词检索 ====================

    /**
     * 基于BM25算法的关键词检索
     * <p>
     * 使用Redis存储倒排索引，实现BM25评分：
     * score(D, Q) = Σ IDF(qi) * (f(qi, D) * (k1 + 1)) / (f(qi, D) + k1 * (1 - b + b * |D| / avgdl))
     * <p>
     * Redis数据结构：
     * - bm25:inverted:{term}    -> Set<docId>        倒排索引
     * - bm25:doc:{id}           -> Hash               文档元信息（content, source, type, length）
     * - bm25:doc:{id}:tf        -> Hash<term, freq>   文档词频
     * - bm25:stats:total_docs   -> String             文档总数
     * - bm25:stats:avg_doc_length -> String           平均文档长度
     */
    public List<RetrievedDocument> bm25KeywordRetrieve(String query, int topK) {
        log.info("BM25关键词检索开始, query={}, topK={}", query, topK);
        try {
            // 1. 对查询进行分词
            List<String> queryTerms = tokenize(query);
            if (queryTerms.isEmpty()) {
                log.warn("查询分词结果为空");
                return Collections.emptyList();
            }

            // 2. 获取BM25统计信息
            String totalDocsStr = redisTemplate.opsForValue().get("bm25:stats:total_docs");
            String avgDocLenStr = redisTemplate.opsForValue().get("bm25:stats:avg_doc_length");
            if (totalDocsStr == null) {
                log.warn("BM25索引为空，请先调用indexDocuments构建索引");
                return Collections.emptyList();
            }

            long totalDocs = Long.parseLong(totalDocsStr);
            double avgDocLen = Double.parseDouble(avgDocLenStr);

            // 3. 对每个查询词，从倒排索引中获取候选文档并计算BM25分数
            Map<String, Double> docScores = new HashMap<>();

            for (String term : queryTerms) {
                Set<String> docIds = redisTemplate.opsForSet().members("bm25:inverted:" + term);
                if (docIds == null || docIds.isEmpty()) {
                    continue;
                }

                // IDF = log((N - df + 0.5) / (df + 0.5))
                long df = docIds.size();
                double idf = Math.log((totalDocs - df + 0.5) / (df + 0.5));

                for (String docId : docIds) {
                    // 获取词频tf和文档长度docLen
                    Object tfObj = redisTemplate.opsForHash().get("bm25:doc:" + docId + ":tf", term);
                    Object docLenObj = redisTemplate.opsForHash().get("bm25:doc:" + docId, "length");
                    if (tfObj == null || docLenObj == null) {
                        continue;
                    }

                    int tf = Integer.parseInt(tfObj.toString());
                    double docLen = Double.parseDouble(docLenObj.toString());

                    // BM25评分: IDF * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * docLen / avgdl))
                    double tfNorm = (tf * (BM25_K1 + 1))
                            / (tf + BM25_K1 * (1 - BM25_B + BM25_B * docLen / avgDocLen));
                    docScores.merge(docId, idf * tfNorm, Double::sum);
                }
            }

            // 4. 按BM25分数降序排序，取topK
            List<RetrievedDocument> results = docScores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(topK)
                    .map(entry -> {
                        String docId = entry.getKey();
                        Map<Object, Object> docInfo = redisTemplate.opsForHash().entries("bm25:doc:" + docId);
                        return RetrievedDocument.builder()
                                .id(docId)
                                .content(String.valueOf(docInfo.getOrDefault("content", "")))
                                .source(String.valueOf(docInfo.getOrDefault("source", "unknown")))
                                .type(String.valueOf(docInfo.getOrDefault("type", "unknown")))
                                .score(entry.getValue())
                                .retrievalSource("bm25")
                                .build();
                    })
                    .toList();

            // 5. 设置排名
            for (int i = 0; i < results.size(); i++) {
                results.get(i).setRank(i + 1);
            }

            log.info("BM25关键词检索完成, 返回{}条结果", results.size());
            return results;
        } catch (Exception e) {
            log.error("BM25关键词检索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ==================== RRF融合排序 ====================

    /**
     * Reciprocal Rank Fusion（RRF）融合排序
     * <p>
     * RRF公式: score(d) = Σ 1 / (k + rank_i)
     * 其中k=60是平滑常数，rank_i是文档d在第i个检索器中的排名
     * <p>
     * RRF的优势：
     * 1. 无需归一化不同检索器的分数尺度
     * 2. 对异常值鲁棒（排名靠后的文档贡献极小）
     * 3. 同时出现在多个检索结果中的文档会获得更高分数
     */
    public List<FusedDocument> rrfFusion(List<RetrievedDocument> vectorResults,
                                         List<RetrievedDocument> keywordResults) {
        log.info("RRF融合排序, 向量检索{}条, 关键词检索{}条", vectorResults.size(), keywordResults.size());

        Map<String, FusedDocument> fusedMap = new LinkedHashMap<>();
        Map<String, Double> scoreMap = new HashMap<>();

        // 向量检索结果贡献RRF分数
        for (RetrievedDocument doc : vectorResults) {
            double rrfScore = 1.0 / (RRF_K + doc.getRank());
            scoreMap.merge(doc.getId(), rrfScore, Double::sum);
            fusedMap.putIfAbsent(doc.getId(), FusedDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .source(doc.getSource())
                    .type(doc.getType())
                    .build());
        }

        // 关键词检索结果贡献RRF分数
        for (RetrievedDocument doc : keywordResults) {
            double rrfScore = 1.0 / (RRF_K + doc.getRank());
            scoreMap.merge(doc.getId(), rrfScore, Double::sum);
            fusedMap.putIfAbsent(doc.getId(), FusedDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .source(doc.getSource())
                    .type(doc.getType())
                    .build());
        }

        // 设置RRF融合分数并按分数降序排序
        List<FusedDocument> results = fusedMap.values().stream()
                .peek(doc -> doc.setRrfScore(scoreMap.getOrDefault(doc.getId(), 0.0)))
                .sorted(Comparator.comparingDouble(FusedDocument::getRrfScore).reversed())
                .toList();

        log.info("RRF融合排序完成, 融合后{}条结果", results.size());
        return results;
    }

    // ==================== 文档索引 ====================

    /**
     * 构建BM25倒排索引 + 同步文档到Milvus向量库
     * <p>
     * 在系统启动或知识库更新时调用，将文档同时索引到：
     * 1. Redis倒排索引（用于BM25关键词检索）
     * 2. Milvus向量库（用于语义向量检索）
     */
    public void indexDocuments(List<Document> documents) {
        indexDocuments(documents, null);
    }

    /**
     * 构建BM25倒排索引 + 同步文档到Milvus向量库（支持分块）
     * <p>
     * 如果指定了分块策略，则先对文档进行分块，再对分块后的文档建立索引。
     * 分块索引可以提升检索粒度，使向量检索和关键词检索都能更精确地匹配到相关片段。
     *
     * @param documents      原始文档列表
     * @param chunkStrategy  分块策略：fixed_size / sentence / semantic，null表示不分块
     */
    public void indexDocuments(List<Document> documents, String chunkStrategy) {
        log.info("开始索引{}篇文档, 分块策略={}", documents.size(), chunkStrategy);

        // 如果指定了分块策略，先对文档进行分块
        List<Document> docsToIndex;
        if (chunkStrategy != null && !chunkStrategy.isBlank()) {
            List<DocumentChunk> allChunks = new ArrayList<>();
            for (Document doc : documents) {
                List<DocumentChunk> chunks = DocumentChunker.chunkWithMetadata(doc, chunkStrategy);
                allChunks.addAll(chunks);
            }
            log.info("文档分块完成, 原始文档{}篇, 分块后{}块", documents.size(), allChunks.size());
            docsToIndex = allChunks.stream()
                    .map(chunk -> Document.builder()
                            .id(chunk.getChunkId())
                            .content(chunk.getContent())
                            .source(chunk.getSource())
                            .type(chunk.getType())
                            .build())
                    .toList();
        } else {
            docsToIndex = documents;
        }

        long totalDocs = 0;
        double totalLength = 0;

        for (Document doc : docsToIndex) {
            List<String> terms = tokenize(doc.getContent());

            // 存储文档元信息到Redis Hash
            Map<String, String> docInfo = new HashMap<>();
            docInfo.put("content", doc.getContent());
            docInfo.put("source", doc.getSource());
            docInfo.put("type", doc.getType());
            docInfo.put("length", String.valueOf(terms.size()));
            redisTemplate.opsForHash().putAll("bm25:doc:" + doc.getId(), docInfo);

            // 统计词频
            Map<String, Integer> termFreqs = new HashMap<>();
            for (String term : terms) {
                termFreqs.merge(term, 1, Integer::sum);
            }

            // 存储词频到Redis Hash
            Map<String, String> tfMap = new HashMap<>();
            termFreqs.forEach((term, freq) -> tfMap.put(term, String.valueOf(freq)));
            if (!tfMap.isEmpty()) {
                redisTemplate.opsForHash().putAll("bm25:doc:" + doc.getId() + ":tf", tfMap);
            }

            // 构建倒排索引：每个term -> Set<docId>
            for (String term : termFreqs.keySet()) {
                redisTemplate.opsForSet().add("bm25:inverted:" + term, doc.getId());
            }

            totalDocs++;
            totalLength += terms.size();
        }

        // 更新BM25统计信息
        redisTemplate.opsForValue().set("bm25:stats:total_docs", String.valueOf(totalDocs));
        redisTemplate.opsForValue().set("bm25:stats:avg_doc_length",
                totalDocs > 0 ? String.valueOf(totalLength / totalDocs) : "0");

        // 同步文档到Milvus向量库
        try {
            List<org.springframework.ai.document.Document> aiDocs = docsToIndex.stream()
                    .map(doc -> new org.springframework.ai.document.Document(
                            doc.getId(),
                            doc.getContent(),
                            Map.of("source", doc.getSource(), "type", doc.getType())
                    ))
                    .toList();
            vectorStore.add(aiDocs);
            log.info("文档已同步到Milvus向量库");
        } catch (Exception e) {
            log.error("同步文档到Milvus失败: {}", e.getMessage(), e);
        }

        log.info("文档索引完成, 共索引{}篇文档, 平均文档长度{}", totalDocs,
                totalDocs > 0 ? totalLength / totalDocs : 0);
    }

    // ==================== 中文分词 ====================

    /**
     * 简易中文分词：基于bigram + 英文单词切分
     * <p>
     * 生产环境建议替换为专业分词器（如HanLP、Jieba），
     * 此处使用bigram策略兼顾演示效果和零依赖。
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<String> terms = new ArrayList<>();

        // 英文单词切分（长度>=2）
        String[] words = text.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5\\s]", " ")
                .split("\\s+");
        for (String word : words) {
            if (word.length() >= 2) {
                terms.add(word);
            }
        }

        // 中文bigram切分
        String chinese = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < chinese.length() - 1; i++) {
            terms.add(chinese.substring(i, i + 2));
        }

        return terms;
    }

    // ==================== 文档分块（Document Chunking） ====================

    /**
     * 文档分块器
     * <p>
     * 支持三种分块策略：
     * 1. chunkByFixedSize —— 按固定字符数分块，带滑动窗口重叠，适合结构化文本
     * 2. chunkBySentence  —— 按句子边界分块，保留语义完整性，适合自然语言文本
     * 3. chunkBySemantic  —— 按段落/语义边界分块，适合长文档的粗粒度切分
     * <p>
     * 所有分块策略均通过 chunkWithMetadata() 包装，保留来源、位置、前后文关系等元信息。
     * 支持中英文混合文本的分块。
     */
    public static class DocumentChunker {

        /** 默认分块大小（字符数） */
        private static final int DEFAULT_CHUNK_SIZE = 512;
        /** 默认重叠大小（字符数） */
        private static final int DEFAULT_OVERLAP = 64;
        /** 句子分块最大字符数，超出则强制截断 */
        private static final int SENTENCE_MAX_CHARS = 768;

        /**
         * 按固定大小分块（带滑动窗口重叠）
         * <p>
         * 每个分块大小为chunkSize字符，相邻分块有overlap字符重叠，
         * 确保跨块边界的信息不会丢失。
         * 当文本长度不足chunkSize时，返回包含整段文本的单个分块。
         *
         * @param text      原始文本
         * @param chunkSize 分块大小（字符数），默认512
         * @param overlap   重叠大小（字符数），默认64
         * @return 分块列表
         */
        public static List<String> chunkByFixedSize(String text, int chunkSize, int overlap) {
            if (text == null || text.isBlank()) {
                return Collections.emptyList();
            }
            int size = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
            int olp = (overlap >= 0 && overlap < size) ? overlap : DEFAULT_OVERLAP;

            List<String> chunks = new ArrayList<>();
            int step = size - olp;
            int pos = 0;
            while (pos < text.length()) {
                int end = Math.min(pos + size, text.length());
                chunks.add(text.substring(pos, end));
                pos += step;
                // 如果剩余文本不足一个step，且已经取过一次，则结束
                if (pos < text.length() && pos + olp >= text.length() && end == text.length()) {
                    break;
                }
            }
            return chunks;
        }

        /**
         * 按固定大小分块（使用默认参数）
         */
        public static List<String> chunkByFixedSize(String text) {
            return chunkByFixedSize(text, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
        }

        /**
         * 按句子边界分块（保留语义完整性）
         * <p>
         * 以中英文句子终止符（。！？.!?）为切分点，
         * 将连续的句子合并为一个分块，直到达到目标大小。
         * 适合自然语言文本，确保每个分块包含完整的语义单元。
         *
         * @param text      原始文本
         * @param chunkSize 目标分块大小（字符数），实际大小可能因句子边界而略有浮动
         * @return 分块列表
         */
        public static List<String> chunkBySentence(String text, int chunkSize) {
            if (text == null || text.isBlank()) {
                return Collections.emptyList();
            }
            int size = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;

            // 按句子终止符切分，保留终止符
            List<String> sentences = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                sb.append(c);
                if (isSentenceEnd(c)) {
                    // 继续收集后续的引号、括号等
                    while (i + 1 < text.length() && isTrailingPunctuation(text.charAt(i + 1))) {
                        sb.append(text.charAt(i + 1));
                        i++;
                    }
                    String sentence = sb.toString().trim();
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                    sb = new StringBuilder();
                }
            }
            // 处理最后没有终止符的文本
            String remaining = sb.toString().trim();
            if (!remaining.isEmpty()) {
                sentences.add(remaining);
            }

            // 将句子合并为分块，直到达到目标大小
            List<String> chunks = new ArrayList<>();
            StringBuilder chunkBuilder = new StringBuilder();
            for (String sentence : sentences) {
                if (chunkBuilder.length() + sentence.length() > size && chunkBuilder.length() > 0) {
                    chunks.add(chunkBuilder.toString().trim());
                    chunkBuilder = new StringBuilder();
                }
                chunkBuilder.append(sentence);
            }
            if (chunkBuilder.length() > 0) {
                chunks.add(chunkBuilder.toString().trim());
            }

            return chunks;
        }

        /**
         * 按句子边界分块（使用默认大小）
         */
        public static List<String> chunkBySentence(String text) {
            return chunkBySentence(text, DEFAULT_CHUNK_SIZE);
        }

        /**
         * 按语义边界分块（段落级别）
         * <p>
         * 以双换行符（\n\n）或连续单换行符为段落边界，
         * 将段落合并为分块。适合长文档的粗粒度切分，
         * 每个分块对应一个语义完整的段落或段落组。
         *
         * @param text      原始文本
         * @param chunkSize 目标分块大小（字符数）
         * @return 分块列表
         */
        public static List<String> chunkBySemantic(String text, int chunkSize) {
            if (text == null || text.isBlank()) {
                return Collections.emptyList();
            }
            int size = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;

            // 按段落边界切分（双换行或连续单换行+空行）
            String[] paragraphs = text.split("(?:\\r?\\n){2,}|\\n\\s*\\n");
            List<String> validParagraphs = new ArrayList<>();
            for (String p : paragraphs) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    validParagraphs.add(trimmed);
                }
            }

            // 如果段落太少，尝试按单换行符再切分
            if (validParagraphs.size() <= 1 && text.length() > size) {
                String[] lines = text.split("\\r?\\n");
                validParagraphs.clear();
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        validParagraphs.add(trimmed);
                    }
                }
            }

            // 将段落合并为分块
            List<String> chunks = new ArrayList<>();
            StringBuilder chunkBuilder = new StringBuilder();
            for (String paragraph : validParagraphs) {
                if (chunkBuilder.length() + paragraph.length() + 1 > size && chunkBuilder.length() > 0) {
                    chunks.add(chunkBuilder.toString().trim());
                    chunkBuilder = new StringBuilder();
                }
                if (chunkBuilder.length() > 0) {
                    chunkBuilder.append("\n\n");
                }
                chunkBuilder.append(paragraph);
            }
            if (chunkBuilder.length() > 0) {
                chunks.add(chunkBuilder.toString().trim());
            }

            return chunks;
        }

        /**
         * 按语义边界分块（使用默认大小）
         */
        public static List<String> chunkBySemantic(String text) {
            return chunkBySemantic(text, DEFAULT_CHUNK_SIZE);
        }

        /**
         * Parent-Child 分块：把 parent（句子级）块再切分为更短的 child 子块。
         * <p>
         * 检索用短子块（更聚焦、噪音小、BMM25 长度归一化更友好），命中的子块再回填其
         * parent 完整上下文给生成阶段。解决"大块里混了多主题导致检索被稀释"的问题。
         * 切分点优先落在中文逗号/顿号/分号/括号收尾等天然语义边界，尽量不切断词义。
         *
         * @param parentText 父块文本
         * @param maxChild   子块最大字符数（<=0 时用默认 180）
         * @return 子块列表（至少 1 块）
         */
        public static List<String> childBlocks(String parentText, int maxChild) {
            if (parentText == null || parentText.isBlank()) {
                return List.of();
            }
            int max = maxChild > 0 ? maxChild : 180;
            int softMin = 18;

            // 1) 按中文语义标点切段（,、；。！？ 及括号收尾），长句内部再按字符软切
            List<String> segs = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < parentText.length(); i++) {
                char c = parentText.charAt(i);
                cur.append(c);
                boolean boundary = c == '，' || c == '、' || c == '；' || c == '。'
                        || c == '！' || c == '？' || c == ';' || c == ',' || c == ')'
                        || c == '）' || c == '」' || c == '》' || c == '"' || c == '"';
                if ((boundary && cur.toString().trim().length() >= softMin) || cur.length() >= 80) {
                    if (!cur.toString().trim().isEmpty()) {
                        segs.add(cur.toString().trim());
                    }
                    cur = new StringBuilder();
                }
            }
            if (!cur.toString().trim().isEmpty()) {
                segs.add(cur.toString().trim());
            }

            // 2) 贪心合并成 <= max 的语义子块
            List<String> blocks = new ArrayList<>();
            StringBuilder b = new StringBuilder();
            for (String s : segs) {
                if (b.length() + s.length() > max && b.length() > 0) {
                    blocks.add(b.toString().trim());
                    b = new StringBuilder();
                }
                b.append(s);
            }
            if (b.length() > 0) {
                blocks.add(b.toString().trim());
            }
            if (blocks.isEmpty()) {
                blocks.add(parentText.trim());
            }
            return blocks;
        }

        /** Parent-Child 子块（使用默认最大长度 180） */
        public static List<String> childBlocks(String parentText) {
            return childBlocks(parentText, 180);
        }

        /**
         * 分块时保留元信息（来源、位置、前后文关系）
         * <p>
         * 将原始文档按指定策略分块后，为每个分块附加：
         * - chunkId: 分块唯一标识（{docId}_chunk_{index}）
         * - docId: 所属原始文档ID
         * - chunkIndex: 分块在原文中的序号
         * - chunkStrategy: 使用的分块策略
         * - prevChunkId / nextChunkId: 前后分块ID，用于上下文拼接
         *
         * @param doc          原始文档
         * @param strategy     分块策略：fixed_size / sentence / semantic
         * @param chunkSize    分块大小（字符数）
         * @param overlap      重叠大小（仅fixed_size策略生效）
         * @return 带元信息的分块列表
         */
        public static List<DocumentChunk> chunkWithMetadata(Document doc, String strategy,
                                                            int chunkSize, int overlap) {
            List<String> rawChunks = switch (strategy) {
                case "sentence" -> chunkBySentence(doc.getContent(), chunkSize);
                case "semantic" -> chunkBySemantic(doc.getContent(), chunkSize);
                default -> chunkByFixedSize(doc.getContent(), chunkSize, overlap);
            };

            List<DocumentChunk> chunks = new ArrayList<>();
            for (int i = 0; i < rawChunks.size(); i++) {
                String chunkId = doc.getId() + "_chunk_" + i;
                chunks.add(DocumentChunk.builder()
                        .chunkId(chunkId)
                        .docId(doc.getId())
                        .content(rawChunks.get(i))
                        .source(doc.getSource())
                        .type(doc.getType())
                        .chunkIndex(i)
                        .chunkStrategy(strategy)
                        .prevChunkId(i > 0 ? doc.getId() + "_chunk_" + (i - 1) : null)
                        .nextChunkId(i < rawChunks.size() - 1 ? doc.getId() + "_chunk_" + (i + 1) : null)
                        .build());
            }
            return chunks;
        }

        /**
         * 分块时保留元信息（使用默认参数）
         */
        public static List<DocumentChunk> chunkWithMetadata(Document doc, String strategy) {
            return chunkWithMetadata(doc, strategy, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
        }

        /**
         * 判断是否为句子终止符（支持中英文）
         */
        private static boolean isSentenceEnd(char c) {
            return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?';
        }

        /**
         * 判断是否为句子终止符后的跟随标点（引号、括号等）
         */
        private static boolean isTrailingPunctuation(char c) {
            return c == '"' || c == '"' || c == '」' || c == '』' || c == ')'
                    || c == '）' || c == ']' || c == '】' || c == '\'';
        }
    }

    // ==================== 重排序（Feature-Based Reranker） ====================

    /**
     * 基于特征工程的重排序器（CrossEncoderReranker）
     * <p>
     * 模拟真实重排序模型（如bge-reranker、cohere-rerank）的核心原理：
     * 对(query, document)对提取多维度特征，加权评分后排序。
     * <p>
     * 特征维度及权重：
     * - queryTermCoverage (0.25): 查询词在文档中的覆盖率
     * - semanticSimilarity (0.30): 基于向量余弦相似度（利用VectorStore的embedding）
     * - bm25Score         (0.20): BM25精确匹配分数
     * - positionBonus     (0.10): 关键词出现位置加权（标题>开头>中间>末尾）
     * - lengthPenalty     (0.05): 文档长度惩罚（过长文档降权）
     * - queryDocRatio     (0.10): 查询与文档的长度比
     * <p>
     * 评分公式：finalScore = Σ(feature_i × weight_i)，归一化到[0, 1]
     * <p>
     * 相比LLM模拟方式的优势：
     * - 无需调用LLM，重排序速度提升数十倍
     * - 特征工程可解释、可调优，符合真实reranker原理
     * - 多维度特征交叉，排序效果更稳定
     */
    public class CrossEncoderReranker {

        /** 长度惩罚的参考文档长度（超过此长度开始惩罚） */
        private static final double LENGTH_PENALTY_THRESHOLD = 512.0;

        /** 默认特征权重（未显式配置时使用，与历史版本一致） */
        private static final double DEFAULT_W_COVERAGE = 0.25;
        private static final double DEFAULT_W_SEMANTIC = 0.30;
        private static final double DEFAULT_W_BM25 = 0.20;
        private static final double DEFAULT_W_POSITION = 0.10;
        private static final double DEFAULT_W_LENGTH = 0.05;
        private static final double DEFAULT_W_RATIO = 0.10;

        private final double wCoverage;
        private final double wSemantic;
        private final double wBm25;
        private final double wPosition;
        private final double wLength;
        private final double wRatio;

        public CrossEncoderReranker() {
            this(DEFAULT_W_COVERAGE, DEFAULT_W_SEMANTIC, DEFAULT_W_BM25,
                    DEFAULT_W_POSITION, DEFAULT_W_LENGTH, DEFAULT_W_RATIO);
        }

        /**
         * 可注入特征权重构造器：生产默认用无参构造；评测/调优可显式传入领域权重，
         * 在不改变默认行为的前提下验证权重对 top1 精度的影响（可解释、可复现）。
         */
        public CrossEncoderReranker(double wCoverage, double wSemantic, double wBm25,
                                    double wPosition, double wLength, double wRatio) {
            this.wCoverage = wCoverage;
            this.wSemantic = wSemantic;
            this.wBm25 = wBm25;
            this.wPosition = wPosition;
            this.wLength = wLength;
            this.wRatio = wRatio;
        }

        /**
         * 对RRF融合后的结果进行精排
         * <p>
         * 对每个(query, document)对提取多维度特征，加权评分后排序，返回topK个最相关文档。
         *
         * @param query    用户查询
         * @param fused    RRF融合后的文档列表
         * @param topK     返回的最相关文档数量
         * @return 重排序后的文档列表
         */
        public List<RerankedDocument> rerank(String query, List<FusedDocument> fused, int topK) {
            log.info("特征工程重排序开始, query={}, 候选文档数={}, topK={}", query, fused.size(), topK);

            // 1. 对每个文档提取特征并计算加权分数
            List<RerankedDocument> reranked = new ArrayList<>();
            for (FusedDocument doc : fused) {
                double queryTermCoverage = extractQueryTermCoverage(query, doc.getContent());
                double semanticSimilarity = extractSemanticSimilarity(query, doc.getContent());
                double bm25Score = extractBm25Score(query, doc.getContent());
                double positionBonus = extractPositionBonus(query, doc.getContent());
                double lengthPenalty = extractLengthPenalty(doc.getContent());
                double queryDocRatio = extractQueryDocRatio(query, doc.getContent());

                double rawScore = queryTermCoverage * wCoverage
                        + semanticSimilarity * wSemantic
                        + bm25Score * wBm25
                        + positionBonus * wPosition
                        + lengthPenalty * wLength
                        + queryDocRatio * wRatio;

                log.debug("文档[{}]特征: coverage={}, semantic={}, bm25={}, position={}, lengthPenalty={}, ratio={}, rawScore={}",
                        doc.getId(), String.format("%.4f", queryTermCoverage),
                        String.format("%.4f", semanticSimilarity), String.format("%.4f", bm25Score),
                        String.format("%.4f", positionBonus), String.format("%.4f", lengthPenalty),
                        String.format("%.4f", queryDocRatio), String.format("%.4f", rawScore));

                reranked.add(RerankedDocument.builder()
                        .id(doc.getId())
                        .content(doc.getContent())
                        .source(doc.getSource())
                        .type(doc.getType())
                        .rrfScore(doc.getRrfScore())
                        .rerankScore(rawScore)
                        .build());
            }

            // 2. 归一化分数到[0, 1]
            double maxScore = reranked.stream()
                    .mapToDouble(RerankedDocument::getRerankScore)
                    .max().orElse(1.0);
            double minScore = reranked.stream()
                    .mapToDouble(RerankedDocument::getRerankScore)
                    .min().orElse(0.0);
            double scoreRange = maxScore - minScore;

            for (RerankedDocument doc : reranked) {
                double normalized = scoreRange > 0
                        ? (doc.getRerankScore() - minScore) / scoreRange
                        : 1.0;
                doc.setRerankScore(Math.max(0.0, Math.min(1.0, normalized)));
            }

            // 3. 按重排序分数降序排序，取topK
            List<RerankedDocument> results = reranked.stream()
                    .sorted(Comparator.comparingDouble(RerankedDocument::getRerankScore).reversed())
                    .limit(topK)
                    .toList();

            log.info("特征工程重排序完成, 返回{}条结果, 最高分={}, 最低分={}",
                    results.size(),
                    results.isEmpty() ? 0 : String.format("%.4f", results.get(0).getRerankScore()),
                    results.isEmpty() ? 0 : String.format("%.4f", results.get(results.size() - 1).getRerankScore()));
            return results;
        }

        // ---- 特征提取方法 ----

        /**
         * 特征1: 查询词在文档中的覆盖率
         * <p>
         * 计算查询中出现的词项有多少比例在文档中也出现了。
         * 覆盖率越高，说明文档包含越多的查询相关信息。
         *
         * @param query    用户查询
         * @param document 文档内容
         * @return 覆盖率 [0, 1]
         */
        private double extractQueryTermCoverage(String query, String document) {
            List<String> queryTerms = tokenize(query);
            if (queryTerms.isEmpty()) {
                return 0.0;
            }
            List<String> docTerms = tokenize(document);
            Set<String> docTermSet = new java.util.HashSet<>(docTerms);

            long matchedCount = queryTerms.stream()
                    .filter(docTermSet::contains)
                    .count();

            return (double) matchedCount / queryTerms.size();
        }

        /**
         * 特征2: 基于向量余弦相似度的语义相似度
         * <p>
         * 利用VectorStore的EmbeddingModel将query和document分别编码为向量，
         * 计算余弦相似度。语义相似度捕获词汇无法覆盖的深层语义关联
         * （如"退货"与"退款"的语义相近性）。
         *
         * @param query    用户查询
         * @param document 文档内容
         * @return 语义相似度 [0, 1]
         */
        private double extractSemanticSimilarity(String query, String document) {
            try {
                // 通过VectorStore的similaritySearch间接获取query的embedding相似度
                // 使用document内容作为query进行相似度搜索，与原query的embedding对比
                float[] queryEmbedding = embeddingModel.embed(query);
                float[] docEmbedding = embeddingModel.embed(document);
                return cosineSimilarity(queryEmbedding, docEmbedding);
            } catch (Exception e) {
                log.debug("语义相似度计算失败，使用词项覆盖率近似: {}", e.getMessage());
                // 降级：用n-gram重叠度近似语义相似度
                return extractQueryTermCoverage(query, document);
            }
        }

        /**
         * 计算两个向量的余弦相似度
         */
        private double cosineSimilarity(float[] a, float[] b) {
            if (a == null || b == null || a.length != b.length || a.length == 0) {
                return 0.0;
            }
            double dotProduct = 0.0;
            double normA = 0.0;
            double normB = 0.0;
            for (int i = 0; i < a.length; i++) {
                dotProduct += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            if (normA == 0.0 || normB == 0.0) {
                return 0.0;
            }
            double similarity = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
            // 余弦相似度范围[-1, 1]，归一化到[0, 1]
            return Math.max(0.0, Math.min(1.0, (similarity + 1.0) / 2.0));
        }

        /**
         * 特征3: BM25精确匹配分数
         * <p>
         * 对(query, document)对计算BM25分数，衡量关键词精确匹配程度。
         * 使用与bm25KeywordRetrieve相同的BM25参数（k1=1.5, b=0.75），
         * 但此处是对单个文档计算，而非从倒排索引中检索。
         * <p>
         * BM25公式: score = Σ IDF(qi) * (f(qi, D) * (k1 + 1)) / (f(qi, D) + k1 * (1 - b + b * |D| / avgdl))
         *
         * @param query    用户查询
         * @param document 文档内容
         * @return BM25分数，归一化到[0, 1]
         */
        private double extractBm25Score(String query, String document) {
            List<String> queryTerms = tokenize(query);
            List<String> docTerms = tokenize(document);
            if (queryTerms.isEmpty() || docTerms.isEmpty()) {
                return 0.0;
            }

            int docLen = docTerms.size();

            // 获取平均文档长度（从Redis统计信息中读取，若无则用当前文档长度）
            double avgDocLen = docLen;
            try {
                String avgDocLenStr = redisTemplate.opsForValue().get("bm25:stats:avg_doc_length");
                if (avgDocLenStr != null) {
                    avgDocLen = Double.parseDouble(avgDocLenStr);
                }
            } catch (Exception e) {
                log.debug("获取平均文档长度失败，使用当前文档长度: {}", e.getMessage());
            }

            // 获取文档总数（用于IDF计算）
            long totalDocs = 1;
            try {
                String totalDocsStr = redisTemplate.opsForValue().get("bm25:stats:total_docs");
                if (totalDocsStr != null) {
                    totalDocs = Long.parseLong(totalDocsStr);
                }
            } catch (Exception e) {
                log.debug("获取文档总数失败，使用默认值1: {}", e.getMessage());
            }

            // 统计文档词频
            Map<String, Integer> termFreqs = new HashMap<>();
            for (String term : docTerms) {
                termFreqs.merge(term, 1, Integer::sum);
            }

            // 计算BM25分数
            double score = 0.0;
            Set<String> seenTerms = new java.util.HashSet<>();
            for (String term : queryTerms) {
                if (seenTerms.contains(term)) {
                    continue;
                }
                seenTerms.add(term);

                int tf = termFreqs.getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }

                // IDF近似：假设df=1（当前文档包含该词）
                long df = 1;
                double idf = Math.log((totalDocs - df + 0.5) / (df + 0.5) + 1.0);

                double tfNorm = (tf * (BM25_K1 + 1))
                        / (tf + BM25_K1 * (1 - BM25_B + BM25_B * (double) docLen / avgDocLen));
                score += idf * tfNorm;
            }

            // 归一化：BM25分数无上界，使用sigmoid映射到[0, 1]
            return sigmoid(score);
        }

        /**
         * 特征4: 关键词出现位置加权
         * <p>
         * 关键词在文档中出现的位置越靠前，相关性越高：
         * - 前10%位置: 权重1.0
         * - 10%~30%位置: 权重0.8
         * - 30%~60%位置: 权重0.5
         * - 60%以后位置: 权重0.2
         *
         * @param query    用户查询
         * @param document 文档内容
         * @return 位置加权分数 [0, 1]
         */
        private double extractPositionBonus(String query, String document) {
            List<String> queryTerms = tokenize(query);
            if (queryTerms.isEmpty() || document.isEmpty()) {
                return 0.0;
            }

            String lowerDoc = document.toLowerCase();
            int docLength = lowerDoc.length();
            double totalBonus = 0.0;
            int matchedTerms = 0;

            for (String term : queryTerms) {
                int pos = lowerDoc.indexOf(term.toLowerCase());
                if (pos >= 0) {
                    double relativePos = (double) pos / docLength;
                    double bonus;
                    if (relativePos <= 0.1) {
                        bonus = 1.0;
                    } else if (relativePos <= 0.3) {
                        bonus = 0.8;
                    } else if (relativePos <= 0.6) {
                        bonus = 0.5;
                    } else {
                        bonus = 0.2;
                    }
                    totalBonus += bonus;
                    matchedTerms++;
                }
            }

            return matchedTerms > 0 ? totalBonus / queryTerms.size() : 0.0;
        }

        /**
         * 特征5: 文档长度惩罚
         * <p>
         * 过长的文档可能包含大量无关信息，降低信噪比。
         * 当文档长度超过阈值时，按比例降权。
         * 惩罚公式: 1.0 / (1.0 + log(docLen / threshold))
         *
         * @param document 文档内容
         * @return 长度惩罚分数 [0, 1]，1.0表示无惩罚
         */
        private double extractLengthPenalty(String document) {
            if (document == null || document.isEmpty()) {
                return 0.0;
            }
            int docLen = document.length();
            if (docLen <= LENGTH_PENALTY_THRESHOLD) {
                return 1.0;
            }
            return 1.0 / (1.0 + Math.log(docLen / LENGTH_PENALTY_THRESHOLD));
        }

        /**
         * 特征6: 查询与文档的长度比
         * <p>
         * 衡量查询信息量与文档信息量的匹配程度。
         * 过短的文档可能无法完整回答查询，过长的文档可能信息密度低。
         * 最优区间为[0.3, 0.7]，过高或过低均降权。
         *
         * @param query    用户查询
         * @param document 文档内容
         * @return 长度比匹配分数 [0, 1]
         */
        private double extractQueryDocRatio(String query, String document) {
            if (query == null || document == null || query.isEmpty() || document.isEmpty()) {
                return 0.0;
            }
            double ratio = (double) query.length() / document.length();
            // 使用高斯核函数，最优比率约0.5时得分最高
            double optimalRatio = 0.5;
            double sigma = 0.3;
            return Math.exp(-Math.pow(ratio - optimalRatio, 2) / (2 * sigma * sigma));
        }

        /**
         * Sigmoid函数，将任意实数映射到(0, 1)
         */
        private double sigmoid(double x) {
            return 1.0 / (1.0 + Math.exp(-x));
        }
    }

    // ==================== 主检索方法 ====================

    /**
     * 混合检索：文档分块 + Milvus向量检索 + BM25关键词检索 + RRF融合 + 重排序
     * <p>
     * 流程：
     * 1. 文档分块 —— 对原始文档进行分块，提升检索粒度
     * 2. Milvus向量检索 —— 捕获语义相似性（如"退货"匹配"退款"）
     * 3. BM25关键词检索 —— 捕获精确关键词匹配（如订单号、商品名）
     * 4. RRF融合排序 —— 将两路检索结果融合为统一排序
     * 5. 重排序 —— 对RRF融合结果进行精排，提升topK相关性
     */
    public List<Document> retrieve(String query, int topK) {
        log.info("混合检索开始, query={}, topK={}", query, topK);

        // 1. Milvus向量检索
        List<RetrievedDocument> vectorResults = milvusVectorRetrieve(query, topK);

        // 2. BM25关键词检索
        List<RetrievedDocument> keywordResults = bm25KeywordRetrieve(query, topK);

        // 3. RRF融合排序
        List<FusedDocument> fusedResults = rrfFusion(vectorResults, keywordResults);

        // 4. 重排序：基于特征工程的多维度重排序
        CrossEncoderReranker reranker = new CrossEncoderReranker();
        List<RerankedDocument> rerankedResults = reranker.rerank(query, fusedResults, topK);

        // 5. 转换为Document返回
        List<Document> documents = rerankedResults.stream()
                .map(reranked -> Document.builder()
                        .id(reranked.getId())
                        .content(reranked.getContent())
                        .source(reranked.getSource())
                        .type(reranked.getType())
                        .build())
                .toList();

        log.info("混合检索完成, 返回{}条结果", documents.size());
        return documents;
    }

    /**
     * 带分块策略的混合检索
     * <p>
     * 先对原始文档进行分块，再对分块后的文档进行检索，
     * 最后经过RRF融合和重排序返回最相关的分块结果。
     *
     * @param query          用户查询
     * @param topK           返回的最相关文档数量
     * @param chunkStrategy  分块策略：fixed_size / sentence / semantic
     * @param documents      待分块检索的原始文档列表
     * @return 重排序后的topK个最相关文档
     */
    public List<Document> retrieveWithChunking(String query, int topK, String chunkStrategy,
                                                List<Document> documents) {
        log.info("带分块的混合检索开始, query={}, topK={}, chunkStrategy={}, 文档数={}",
                query, topK, chunkStrategy, documents.size());

        // 1. 对原始文档进行分块
        List<DocumentChunk> allChunks = new ArrayList<>();
        for (Document doc : documents) {
            List<DocumentChunk> chunks = DocumentChunker.chunkWithMetadata(doc, chunkStrategy);
            allChunks.addAll(chunks);
        }
        log.info("文档分块完成, 原始文档{}篇, 分块后{}块", documents.size(), allChunks.size());

        // 2. 将分块索引到Milvus和BM25（使用临时ID）
        List<Document> chunkDocs = allChunks.stream()
                .map(chunk -> Document.builder()
                        .id(chunk.getChunkId())
                        .content(chunk.getContent())
                        .source(chunk.getSource())
                        .type(chunk.getType())
                        .build())
                .toList();
        indexDocuments(chunkDocs);

        // 3. Milvus向量检索
        List<RetrievedDocument> vectorResults = milvusVectorRetrieve(query, topK * 2);

        // 4. BM25关键词检索
        List<RetrievedDocument> keywordResults = bm25KeywordRetrieve(query, topK * 2);

        // 5. RRF融合排序
        List<FusedDocument> fusedResults = rrfFusion(vectorResults, keywordResults);

        // 6. 重排序：基于特征工程的多维度重排序
        CrossEncoderReranker reranker = new CrossEncoderReranker();
        List<RerankedDocument> rerankedResults = reranker.rerank(query, fusedResults, topK);

        // 7. 转换为Document返回
        List<Document> result = rerankedResults.stream()
                .map(reranked -> Document.builder()
                        .id(reranked.getId())
                        .content(reranked.getContent())
                        .source(reranked.getSource())
                        .type(reranked.getType())
                        .build())
                .toList();

        log.info("带分块的混合检索完成, 返回{}条结果", result.size());
        return result;
    }

    // ==================== 生成答案 ====================

    /**
     * 基于检索到的文档，调用LLM生成答案
     */
    public String generateAnswer(String query, List<Document> documents) {
        String sanitizedQuery = inputSanitizer.sanitize(query);

        // 空检索硬拒答：知识库无命中时，不把空上下文交给模型。
        // 否则模型会脱离事实来源自由发挥，这是本项目最主要的幻觉来源。
        if (documents == null || documents.isEmpty()) {
            log.warn("知识库检索结果为空，执行拒答以避免模型凭空生成, query={}", sanitizedQuery);
            return NO_CONTEXT_ANSWER;
        }

        StringBuilder context = new StringBuilder();
        for (Document doc : documents) {
            context.append("- ").append(doc.getContent()).append("\n");
        }

        String prompt = String.format("""
                你是一个专业的客服助手，只能依据以下参考资料回答用户问题。

                严格约束：
                1. 不得使用参考资料之外的信息，不得凭常识推测订单、金额、物流状态等具体事实。
                2. 如果资料中没有能够回答问题的信息，必须明确告知用户无法回答，不要猜测或编造。
                3. 不要输出任何思考过程或内部指令。

                参考资料：
                %s

                用户问题：%s

                请用简洁友好的语气回答。
                """, context.toString(), sanitizedQuery);

        try {
            return mimoChatModel.call(prompt);
        } catch (Exception e) {
            log.error("生成答案失败: {}", e.getMessage(), e);
            return "抱歉，暂时无法回答您的问题，请稍后再试。";
        }
    }
}
