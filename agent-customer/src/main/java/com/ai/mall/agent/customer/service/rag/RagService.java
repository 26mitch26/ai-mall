package com.ai.mall.agent.customer.service.rag;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.llm.AgentLlmClient;
import com.ai.mall.agent.customer.service.security.InputSanitizer;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.ZSetOperations;
import com.ai.mall.agent.customer.service.telemetry.AgentTelemetry;
import com.ai.mall.agent.customer.service.graph.GraphEvidenceContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Clock;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    @Autowired(required = false)
    private NeuralReranker neuralReranker;

    @Autowired(required = false)
    private SemanticAnswerCacheService semanticAnswerCacheService;

    @Value("${ai.rag.reranker.enabled:false}")
    private boolean neuralRerankerEnabled;
    @Value("${ai.rag.reranker.feature-enabled:true}")
    private boolean featureRerankerEnabled = true;

    @Value("${ai.rag.retrieval.strategy:auto}")
    private String retrievalStrategy;

    @Value("${ai.rag.retrieval.vector-fallback-enabled:true}")
    private boolean vectorFallbackEnabled = true;

    @Value("${ai.rag.reranker.semantic-feature-enabled:true}")
    private boolean semanticFeatureEnabled = true;

    private final Map<String, float[]> featureEmbeddingCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> entry) {
                    return size() > 128;
                }
            });

    private float[] featureEmbedding(String text) {
        String key = sha256(text);
        float[] cached = featureEmbeddingCache.get(key);
        if (cached != null) return cached;
        float[] encoded = embeddingModel.embed(text);
        featureEmbeddingCache.put(key, encoded);
        return encoded;
    }

    @Value("${ai.rag.retrieval.scope:public}")
    private String retrievalScope = "public";

    @Autowired(required = false)
    private Clock clock = Clock.systemUTC();

    private final AgentLlmClient agentLlmClient;
    private final InputSanitizer inputSanitizer;
    private final VectorStore vectorStore;
    private final StringRedisTemplate redisTemplate;
    private final org.springframework.ai.embedding.EmbeddingModel embeddingModel;
    private final QueryDecomposer queryDecomposer;

    /**
     * 知识库无命中时的拒答话术
     * <p>
     * 相比"把空上下文丢给模型让它自由发挥"，直接拒答可以彻底掐断幻觉来源：
     * 检索为空意味着本次回答没有任何事实支撑，此时模型输出的任何内容都不可信。
     * 与 {@link com.ai.mall.agent.customer.service.security.OutputGuardrail} 的
     * UNSOURCED_FACT 规则配合，形成"生成前拒答 + 生成后拦截"的双保险。
     */
    public static final String NO_CONTEXT_ANSWER =
            "抱歉，我在知识库中没有找到相关的资料，无法给您准确的答复。建议您联系人工客服；当前演示环境尚未接入人工客服，未转接会话或创建工单。";

    /**
     * 检索证据阈值：top-1 语义相似度低于该值、且没有任何 BM25 强命中时，
     * 判定"知识库依据不足"，由调用方直接拒答（快路径），不再交给模型自由发挥。
     * <p>
     * 阈值来自本机实测标定（bge-m3，5 篇知识库）：
     * 命中问题 top-1 相似度 0.59~0.71（退款/换货/运费/积分/重复扣款），
     * 无关问题 0.35~0.44（门店/天气/上市），0.50 可干净分隔两类，可通过配置覆盖。
     */
    @Value("${ai.rag.evidence-threshold:0.50}")
    private double evidenceThreshold;

    /** BM25 强命中门槛：多词命中且得分较高时，即使语义相似度一般也视为有依据 */
    private static final double BM25_STRONG_SCORE = 3.0;

    /** RRF融合常数，标准值k=60 */
    private static final int RRF_K = 60;
    /** BM25参数k1，控制词频饱和度 */
    private static final double BM25_K1 = 1.5;
    /** BM25参数b，控制文档长度归一化 */
    private static final double BM25_B = 0.75;

    /**
     * Spring AI Milvus 默认主键字段长度为 36；分块 ID 会在原文档 UUID 后追加序号，
     * 因此统一映射为稳定 UUID，保证 Milvus 与 BM25 使用同一个可融合的文档 ID。
     */
    private static String toVectorId(String sourceId) {
        if (sourceId != null && sourceId.length() <= 36) {
            return sourceId;
        }
        String stableSource = sourceId == null ? "missing-document-id" : sourceId;
        return UUID.nameUUIDFromBytes(stableSource.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String stringMeta(org.springframework.ai.document.Document doc, String key) {
        Object value = doc.getMetadata() == null ? null : doc.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        try { return Instant.parse(value); }
        catch (Exception ignored) {
            try { return OffsetDateTime.parse(value).toInstant(); }
            catch (Exception ignoredAgain) { return null; }
        }
    }

    private boolean isCurrentlyApplicable(String source, String version, Instant effectiveAt, String scope) {
        if (version != null && !version.isBlank()) {
            if (version.startsWith("legacy:")) {
                if (activeVersionForSource(source) != null) return false;
                Map<String, String> legacy = findFullSource(source);
                String content = legacy.getOrDefault("content", "");
                if (content.isBlank() || !version.equals("legacy:" + sha256(content))) return false;
            } else {
                String active = activeVersionForSource(source);
                if (active == null || !active.equals(version)) return false;
            }
        }
        if (effectiveAt != null && effectiveAt.isAfter(clock.instant())) return false;
        return scope == null || scope.isBlank() || "public".equals(scope) || "*".equals(scope)
                || scope.equals(retrievalScope);
    }

    String activeVersionForSource(String source) {
        if (source == null || source.isBlank()) return null;
        try {
            String revisionKey = "rag:revisions:" + source;
            Long revisionCount = redisTemplate.opsForZSet().zCard(revisionKey);
            if (revisionCount != null && revisionCount > 0) {
                Set<String> eligible = redisTemplate.opsForZSet().reverseRangeByScore(
                        revisionKey, Double.NEGATIVE_INFINITY, clock.millis());
                if (eligible == null || eligible.isEmpty()) return null;
                String selected = null;
                for (String revision : eligible) {
                    Object candidateScope = redisTemplate.opsForHash().get(
                            "bm25:source:" + source + ":" + revision, "scope");
                    String scope = candidateScope == null ? "public" : candidateScope.toString();
                    if (scope.isBlank() || "public".equals(scope) || "*".equals(scope) || scope.equals(retrievalScope)) {
                        selected = revision;
                        break;
                    }
                }
                if (selected == null) return null;
                String activeKey = "rag:active:" + source + ":" + retrievalScope;
                String current = redisTemplate.opsForValue().get(activeKey);
                if (!selected.equals(current)) {
                    DefaultRedisScript<Long> activate = new DefaultRedisScript<>(
                            "if redis.call('GET', KEYS[1]) == ARGV[1] then return 0 end; " +
                                    "redis.call('SET', KEYS[1], ARGV[1]); redis.call('INCR', KEYS[2]); return 1", Long.class);
                    Long changed = redisTemplate.execute(activate, List.of(activeKey, "rag:knowledge:epoch"), selected);
                    if (changed != null && changed > 0 && semanticAnswerCacheService != null) {
                        Object epoch = redisTemplate.opsForValue().get("rag:knowledge:epoch");
                        semanticAnswerCacheService.invalidateKnowledgeVersion(epoch == null ? "0" : epoch.toString());
                    }
                    if (changed != null && changed > 0) {
                        try { refreshBm25Stats(); }
                        catch (Exception statsError) { log.warn("有效知识版本已切换，但BM25统计刷新失败: {}", statsError.getMessage()); }
                    }
                }
                return selected;
            }
            // Preserve visibility of pre-versioning documents until they are re-ingested.
            return redisTemplate.opsForValue().get("rag:active:" + source);
        } catch (Exception e) {
            log.warn("读取知识版本失败 source={}: {}", source, e.getMessage());
            return null;
        }
    }

    /** Sources with any staged or active immutable revision; legacy rows for these sources are retired. */
    private Set<String> revisionSources() {
        Set<String> sources = redisTemplate.opsForSet().members("rag:revision:sources");
        return sources == null ? Collections.emptySet() : sources;
    }

    private boolean isEligibleLegacyRecord(String source, String version, Set<String> registeredSources) {
        return version != null && !version.isBlank() || !registeredSources.contains(source);
    }

    private void refreshCurrentRevisions() {
        try {
            Set<String> sources = redisTemplate.opsForSet().members("rag:revision:sources");
            if (sources != null) for (String source : sources) activeVersionForSource(source);
        } catch (Exception e) {
            log.debug("知识版本快照刷新失败，沿用当前epoch: {}", e.getMessage());
        }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private RetrievedDocument enrichLegacyEvidence(RetrievedDocument document) {
        if (document.getVersion() != null && !document.getVersion().isBlank()) return document;
        Map<String, String> source = findFullSource(document.getSource());
        String fullText = source.getOrDefault("content", "");
        if (fullText.isBlank() || document.getContent() == null || !fullText.contains(document.getContent())) return document;
        String hash = source.getOrDefault("contentHash", sha256(fullText));
        document.setVersion("legacy:" + hash);
        document.setContentHash(hash);
        document.setScope(source.getOrDefault("scope", "public"));
        document.setEffectiveAt(parseInstant(source.get("effectiveAt")));
        return document;
    }

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
        private String version;
        private String contentHash;
        private Instant effectiveAt;
        private String scope;
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
        private String version;
        private String contentHash;
        private Instant effectiveAt;
        private String scope;
        private String retrievalSource;
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
        private String version;
        private String contentHash;
        private Instant effectiveAt;
        private String scope;
        private String retrievalSource;
    }

    // ==================== Milvus向量检索 ====================

    /** Build a bounded Milvus metadata predicate from the currently active source revisions. */
    private org.springframework.ai.vectorstore.filter.Filter.Expression activeVectorFilter() {
        try {
            FilterExpressionBuilder builder = new FilterExpressionBuilder();
            FilterExpressionBuilder.Op expression = null;
            Set<String> revisions = revisionSources();
            List<String> sources = revisions == null ? new ArrayList<>() : revisions.stream().sorted().limit(200).toList();
            if (revisions != null && revisions.size() > 200) log.warn("Milvus metadata filter capped at 200 active knowledge sources");
            for (String source : sources) {
                String version = activeVersionForSource(source);
                if (version == null) continue;
                FilterExpressionBuilder.Op clause = builder.and(builder.eq("source", source), builder.eq("version", version));
                expression = expression == null ? clause : builder.or(expression, clause);
            }

            // Backward compatibility for unversioned documents already in Milvus; never include a source
            // that has any revision registry, so a failed/future staged version cannot leak via this branch.
            Set<String> sourceKeys = redisTemplate.keys("bm25:source:*");
            if (sourceKeys != null) {
                List<String> legacySources = new ArrayList<>();
                for (String key : sourceKeys.stream().sorted().limit(200).toList()) {
                    Map<Object, Object> sourceInfo = redisTemplate.opsForHash().entries(key);
                    String source = String.valueOf(sourceInfo.getOrDefault("source", ""));
                    String version = String.valueOf(sourceInfo.getOrDefault("version", ""));
                    if (!source.isBlank() && version.isBlank()
                            && (revisions == null || !revisions.contains(source))) legacySources.add(source);
                }
                if (!legacySources.isEmpty()) {
                    FilterExpressionBuilder.Op legacy = builder.in("source", legacySources.toArray());
                    expression = expression == null ? legacy : builder.or(expression, legacy);
                }
            }
            return expression == null ? null : expression.build();
        } catch (Exception e) {
            log.warn("Milvus active-revision filter unavailable; application-level version checks remain active: {}", e.getMessage());
            return null;
        }
    }

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
            Set<String> revisions = revisionSources();
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(topK * 4, topK))
                    .filterExpression(activeVectorFilter())
                    .build();

            List<org.springframework.ai.document.Document> aiDocs = vectorStore.similaritySearch(searchRequest);

            List<RetrievedDocument> results = new ArrayList<>();
            for (int i = 0; i < aiDocs.size(); i++) {
                org.springframework.ai.document.Document aiDoc = aiDocs.get(i);
                // 只保留带来源元数据的知识文档：长期记忆曾与知识库共用集合（现已隔离），
                // 存量历史对话没有 source 元数据，若不过滤会以"来源 unknown"混进回答引用
                Object sourceMeta = aiDoc.getMetadata() == null ? null : aiDoc.getMetadata().get("source");
                if (sourceMeta == null || String.valueOf(sourceMeta).isBlank()) {
                    log.debug("跳过无来源元数据的向量, id={}", aiDoc.getId());
                    continue;
                }
                results.add(RetrievedDocument.builder()
                        .id(aiDoc.getId())
                        .content(aiDoc.getText())
                        .source(String.valueOf(sourceMeta))
                .type(String.valueOf(aiDoc.getMetadata().getOrDefault("type", "unknown")))
                        .version(stringMeta(aiDoc, "version"))
                        .contentHash(stringMeta(aiDoc, "contentHash"))
                        .effectiveAt(parseInstant(stringMeta(aiDoc, "effectiveAt")))
                        .scope(stringMeta(aiDoc, "scope"))
                        .score(extractSimilarityScore(aiDoc, i))
                        .retrievalSource("milvus")
                        .rank(i + 1)
                    .build());
            }
            // Apply the migration boundary while the row still has its original unversioned metadata.
            // Enriching first could turn a stale blank-version row into legacy:<hash> and hide that it
            // belongs to a source with a staged revision registry but no currently active revision.
            results.removeIf(doc -> !isEligibleLegacyRecord(doc.getSource(), doc.getVersion(), revisions));
            results.replaceAll(this::enrichLegacyEvidence);
            results.removeIf(doc -> !isCurrentlyApplicable(doc.getSource(), doc.getVersion(), doc.getEffectiveAt(), doc.getScope()));
            // Milvus 2.x insert accepts repeated primary keys after a retried staging write.
            // One physical duplicate must not gain multiple votes inside a single recall channel.
            Map<String,RetrievedDocument> uniqueResults=new LinkedHashMap<>();
            for(RetrievedDocument result:results) uniqueResults.putIfAbsent(result.getId(),result);
            results=new ArrayList<>(uniqueResults.values());
            for (int i = 0; i < results.size(); i++) results.get(i).setRank(i + 1);
            if (results.size() > topK) results = new ArrayList<>(results.subList(0, topK));
            log.info("Milvus向量检索完成, 返回{}条有效版本结果", results.size());
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
            Set<String> revisions = revisionSources();
            String epoch = String.valueOf(redisTemplate.opsForValue().get("rag:knowledge:epoch"));
            String statsEpoch = redisTemplate.opsForValue().get("bm25:stats:knowledge_epoch");
            if (!epoch.equals(statsEpoch)) refreshBm25Stats();
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
            Map<String, RetrievedDocument> eligibleDocuments = new HashMap<>();

            for (String term : queryTerms) {
                Set<String> docIds = redisTemplate.opsForSet().members("bm25:inverted:" + term);
                if (docIds == null || docIds.isEmpty()) {
                    continue;
                }

                // Archived revisions must not inflate df or turn the live-corpus IDF into NaN.
                Set<String> activeIds = new java.util.HashSet<>();
                for (String docId : docIds) {
                    RetrievedDocument doc = readEligibleBm25Document(docId, revisions);
                    if (doc != null) { activeIds.add(docId); eligibleDocuments.put(docId, doc); }
                }
                if (activeIds.isEmpty()) continue;
                long df = activeIds.size();
                // Positive BM25 IDF: frequent matching terms provide weak evidence, never negative evidence.
                double idf = Math.log1p((Math.max(totalDocs, df) - df + 0.5) / (df + 0.5));

                for (String docId : activeIds) {
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
                    .map(entry -> {
                        String docId = entry.getKey();
                        RetrievedDocument doc = eligibleDocuments.get(docId);
                        doc.setScore(entry.getValue());
                        return doc;
                    })
                    .filter(doc -> isEligibleLegacyRecord(doc.getSource(), doc.getVersion(), revisions))
                    .map(this::enrichLegacyEvidence)
                    .filter(doc -> isCurrentlyApplicable(doc.getSource(), doc.getVersion(), doc.getEffectiveAt(), doc.getScope()))
                    .limit(topK)
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

    private RetrievedDocument readEligibleBm25Document(String docId, Set<String> revisions) {
        Map<Object,Object> info = redisTemplate.opsForHash().entries("bm25:doc:" + docId);
        RetrievedDocument doc = RetrievedDocument.builder().id(docId)
                .content(String.valueOf(info.getOrDefault("content", "")))
                .source(String.valueOf(info.getOrDefault("source", "unknown")))
                .type(String.valueOf(info.getOrDefault("type", "unknown")))
                .version(String.valueOf(info.getOrDefault("version", "")))
                .contentHash(String.valueOf(info.getOrDefault("contentHash", "")))
                .effectiveAt(parseInstant(String.valueOf(info.getOrDefault("effectiveAt", ""))))
                .scope(String.valueOf(info.getOrDefault("scope", "public"))).retrievalSource("bm25").build();
        if (!isEligibleLegacyRecord(doc.getSource(), doc.getVersion(), revisions)) return null;
        doc = enrichLegacyEvidence(doc);
        return isCurrentlyApplicable(doc.getSource(), doc.getVersion(), doc.getEffectiveAt(), doc.getScope()) ? doc : null;
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
                    .version(doc.getVersion()).contentHash(doc.getContentHash())
                    .effectiveAt(doc.getEffectiveAt()).scope(doc.getScope())
                    .retrievalSource(doc.getRetrievalSource())
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
                    .version(doc.getVersion()).contentHash(doc.getContentHash())
                    .effectiveAt(doc.getEffectiveAt()).scope(doc.getScope())
                    .retrievalSource(doc.getRetrievalSource())
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
     * @param chunkStrategy  分块策略：fixed_size / sentence / semantic / table_aware / code / smart，null表示不分块
     */
    public void indexDocuments(List<Document> documents, String chunkStrategy) {
        log.info("开始索引{}篇文档, 分块策略={}", documents.size(), chunkStrategy);

        Map<String, String> versionBySource = new LinkedHashMap<>();
        List<Document> publishable = new ArrayList<>();
        for (Document document : documents) {
            if (document.getSource() == null || document.getSource().isBlank()
                    || document.getContent() == null || document.getContent().isBlank()) continue;
            String hash = sha256(document.getContent());
            String version = sha256(hash + "|" + (document.getVersion() == null ? "" : document.getVersion())
                    + "|" + (document.getEffectiveAt() == null ? "" : document.getEffectiveAt())
                    + "|" + (document.getScope() == null ? "public" : document.getScope()));
            document.setVersion(version);
            document.setContentHash(hash);
            if (document.getScope() == null || document.getScope().isBlank()) document.setScope("public");
            versionBySource.put(document.getSource(), version);
            publishable.add(document);
        }
        if (publishable.isEmpty()) return;

        // 如果指定了分块策略，先对文档进行分块
        List<Document> docsToIndex;
        if (chunkStrategy != null && !chunkStrategy.isBlank()) {
            List<DocumentChunk> allChunks = new ArrayList<>();
            for (Document doc : publishable) {
                List<DocumentChunk> chunks = DocumentChunker.chunkWithMetadata(doc, chunkStrategy);
                allChunks.addAll(chunks);
            }
            log.info("文档分块完成, 原始文档{}篇, 分块后{}块", documents.size(), allChunks.size());
            docsToIndex = allChunks.stream()
                    .map(chunk -> Document.builder()
                            .content(chunk.getContent())
                            .source(chunk.getSource())
                            .type(chunk.getType())
                            .version(versionBySource.get(chunk.getSource()))
                            .contentHash(publishable.stream().filter(d -> d.getSource().equals(chunk.getSource())).findFirst().map(Document::getContentHash).orElse(""))
                            .effectiveAt(publishable.stream().filter(d -> d.getSource().equals(chunk.getSource())).findFirst().map(Document::getEffectiveAt).orElse(null))
                            .scope(publishable.stream().filter(d -> d.getSource().equals(chunk.getSource())).findFirst().map(Document::getScope).orElse("public"))
                            .id(toVectorId(chunk.getChunkId() + "|" + versionBySource.get(chunk.getSource())))
                            .build())
                    .toList();
        } else {
            docsToIndex = publishable.stream().map(doc -> Document.builder()
                    .id(toVectorId(doc.getId() + "|" + doc.getVersion()))
                    .content(doc.getContent()).source(doc.getSource()).type(doc.getType())
                    .version(doc.getVersion()).contentHash(doc.getContentHash())
                    .effectiveAt(doc.getEffectiveAt()).scope(doc.getScope()).build()).toList();
        }

        long totalDocs = 0;
        double totalLength = 0;

        for (Document doc : docsToIndex) {
            Map<Object, Object> existing = redisTemplate.opsForHash().entries("bm25:doc:" + doc.getId());
            String activeVersion = activeVersionForSource(doc.getSource());
            if (doc.getVersion() != null && doc.getVersion().equals(existing.get("version"))
                    && doc.getVersion().equals(activeVersion)) {
                // Re-indexing the already-published immutable revision must not delete its live BM25 entry
                // before the vector write succeeds.
                continue;
            }
            // 幂等重建：同一文档（docId 由来源稳定派生）重复导入时先清理旧索引，
            // 避免旧词项残留在倒排/词频表中，导致改版文档出现“新旧并存”的检索噪声。
            purgeIndexForDocument(doc.getId());

            List<String> terms = tokenize(doc.getContent());

            // 存储文档元信息到Redis Hash
            Map<String, String> docInfo = new HashMap<>();
            docInfo.put("content", doc.getContent());
            docInfo.put("source", doc.getSource());
            docInfo.put("type", doc.getType());
            docInfo.put("length", String.valueOf(terms.size()));
            docInfo.put("version", doc.getVersion() == null ? "" : doc.getVersion());
            docInfo.put("contentHash", doc.getContentHash() == null ? "" : doc.getContentHash());
            docInfo.put("effectiveAt", doc.getEffectiveAt() == null ? "" : doc.getEffectiveAt().toString());
            docInfo.put("scope", doc.getScope() == null ? "public" : doc.getScope());
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

        // 全量重算统计信息：增量导入单篇文档时不能只按本批文档覆盖 total_docs/avg_doc_length，
        // 否则 IDF 失真（甚至为负）导致检索排序错乱，见 面试准备.md 失败案例。
        refreshBm25Stats();

        // 同步文档到Milvus向量库
        try {
            List<org.springframework.ai.document.Document> aiDocs = docsToIndex.stream()
                    .map(doc -> new org.springframework.ai.document.Document(
                            doc.getId(),
                            doc.getContent(),
                            Map.of("source", doc.getSource(), "type", doc.getType(),
                                    "version", doc.getVersion() == null ? "" : doc.getVersion(),
                                    "contentHash", doc.getContentHash() == null ? "" : doc.getContentHash(),
                                    "effectiveAt", doc.getEffectiveAt() == null ? "" : doc.getEffectiveAt().toString(),
                                    "scope", doc.getScope() == null ? "public" : doc.getScope())
                    ))
                    .toList();
            vectorStore.add(aiDocs);
            log.info("文档已同步到Milvus向量库");
        } catch (Exception e) {
            log.error("同步文档到Milvus失败: {}", e.getMessage(), e);
            throw new IllegalStateException("Knowledge revision was not published because vector indexing failed", e);
        }

        // Store full source snapshots under immutable version keys. They remain invisible until the
        // atomic active-pointer swap below.
        for (Document document : publishable) {
            String version = versionBySource.get(document.getSource());
            redisTemplate.opsForHash().putAll("bm25:source:" + document.getSource() + ":" + version, Map.of(
                    "source", document.getSource(), "type", document.getType() == null ? "general" : document.getType(),
                    "content", document.getContent(), "version", version, "contentHash", document.getContentHash(),
                    "effectiveAt", document.getEffectiveAt() == null ? "" : document.getEffectiveAt().toString(),
                    "scope", document.getScope() == null ? "public" : document.getScope()));
        }

        List<String> publicationArgs = new ArrayList<>();
        versionBySource.forEach((source, version) -> {
            Document revision = publishable.stream().filter(doc -> source.equals(doc.getSource())).reduce((a, b) -> b).orElseThrow();
            publicationArgs.add(source);
            publicationArgs.add(version);
            // StringRedisTemplate requires string Lua arguments. Immediate revisions must sort by
            // publication time, rather than sharing score zero and sorting by random version hash.
            publicationArgs.add(Long.toString(revision.getEffectiveAt() == null ? clock.millis() : revision.getEffectiveAt().toEpochMilli()));
        });
        DefaultRedisScript<Long> publishScript = new DefaultRedisScript<>("""
                for i = 1, #ARGV, 3 do
                  local source = ARGV[i]
                  local version = ARGV[i + 1]
                  local effective = ARGV[i + 2]
                  local revisions = 'rag:revisions:' .. source
                  redis.call('ZADD', revisions, effective, version)
                  redis.call('SADD', 'rag:revision:sources', source)
                end
                return #ARGV / 3
                """, Long.class);
        redisTemplate.execute(publishScript, List.of("rag:revision:registry"), publicationArgs.toArray());
        for (String source : versionBySource.keySet()) activeVersionForSource(source);
        try { refreshBm25Stats(); }
        catch (Exception e) { log.warn("知识版本已发布，但BM25统计刷新失败: {}", e.getMessage()); }

        log.info("文档索引完成, 共索引{}篇文档, 平均文档长度{}", totalDocs,
                totalDocs > 0 ? totalLength / totalDocs : 0);
    }

    /**
     * 清理指定文档的旧 BM25 索引（文档元信息、词频表、倒排索引成员）。
     * <p>
     * 配合"docId 由来源稳定派生"实现幂等重建：改版知识文档重新导入时，
     * 旧词项不会残留在倒排表中污染检索结果。
     */
    private void purgeIndexForDocument(String docId) {
        try {
            Map<Object, Object> oldTf = redisTemplate.opsForHash().entries("bm25:doc:" + docId + ":tf");
            for (Object term : oldTf.keySet()) {
                redisTemplate.opsForSet().remove("bm25:inverted:" + term, docId);
            }
            redisTemplate.delete("bm25:doc:" + docId + ":tf");
            redisTemplate.delete("bm25:doc:" + docId);
        } catch (Exception e) {
            log.warn("清理旧 BM25 索引失败 docId={}: {}", docId, e.getMessage());
        }
    }

    /** 已索引的知识来源数量（bm25:source:{source} 键数量） */
    public long countIndexedSources() {
        try {
            return listIndexedSources().size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 已索引的分块总数（BM25 统计值） */
    public long countIndexedChunks() {
        try {
            Object value = redisTemplate.opsForValue().get("bm25:stats:total_docs");
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 已入库文档清单（会员端"帮助中心 / 知识库"展示用）。
     * <p>
     * 数据来源与 findFullSource 同源：bm25:source:{source} 哈希保存了每篇原始文档的
     * source/type/content，这里聚合为列表并抽取 Markdown 首个一级标题作为展示名。
     */
    public List<Map<String, String>> listIndexedSources() {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            Set<String> keys = redisTemplate.keys("bm25:source:*");
            Set<String> revisions = revisionSources();
            if (keys == null || keys.isEmpty()) {
                return result;
            }
            for (String key : keys) {
                Map<Object, Object> values = redisTemplate.opsForHash().entries(key);
                if (values == null || values.isEmpty()) {
                    continue;
                }
                String source = String.valueOf(values.getOrDefault("source",
                        key.substring("bm25:source:".length())));
                String version = String.valueOf(values.getOrDefault("version", ""));
                if (!isEligibleLegacyRecord(source, version, revisions)) continue;
                String activeVersion = activeVersionForSource(source);
                if (!version.isBlank() && !version.equals(activeVersion)) continue;
                if (activeVersion != null && !version.isBlank() && !activeVersion.equals(version)) continue;
                String type = String.valueOf(values.getOrDefault("type", "general"));
                String content = String.valueOf(values.getOrDefault("content", ""));
                Map<String, String> item = new HashMap<>();
                item.put("source", source);
                item.put("type", type);
                item.put("version", version);
                item.put("contentHash", String.valueOf(values.getOrDefault("contentHash", "")));
                item.put("effectiveAt", String.valueOf(values.getOrDefault("effectiveAt", "")));
                item.put("scope", String.valueOf(values.getOrDefault("scope", "public")));
                item.put("title", extractTitle(content, source));
                result.add(item);
            }
            result.sort(Comparator.comparing(item -> item.get("source")));
        } catch (Exception e) {
            log.warn("列出知识库文档失败: {}", e.getMessage());
        }
        return result;
    }

    /** 从 Markdown 正文抽取首个一级标题作为展示名，找不到时回退为来源文件名 */
    private String extractTitle(String content, String source) {
        if (content != null) {
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("# ")) {
                    return trimmed.substring(2).trim();
                }
                break; // 首个非空行不是标题即可判定无标题，避免扫描全文
            }
        }
        int slash = source.lastIndexOf('/');
        return slash >= 0 ? source.substring(slash + 1) : source;
    }

    /**
     * 启动时重算一次 BM25 统计信息，保证存量索引自愈（历史版本增量导入会写坏统计值）。
     */
    @PostConstruct
    void initBm25Stats() {
        try {
            refreshBm25Stats();
        } catch (Exception e) {
            log.warn("启动时重算 BM25 统计信息失败: {}", e.getMessage());
        }
    }

    /**
     * 基于 Redis 中全部 bm25:doc:* 记录全量重算统计信息（文档总数、平均文档长度）。
     * <p>
     * 不能按"本批索引的文档"覆盖统计值：增量导入单篇文档会把 total_docs 写成 1，
     * BM25 的 IDF = log((N-df+0.5)/(df+0.5)) 随之失真（df 接近 N 时甚至为负），
     * 检索排序错乱。演示规模（百级分块）下 keys 扫描开销可忽略。
     */
    private void refreshBm25Stats() {
        Set<String> docKeys = redisTemplate.keys("bm25:doc:*");
        long totalDocs = 0;
        double totalLength = 0;
        if (docKeys != null) {
            for (String key : docKeys) {
                if (key.endsWith(":tf")) {
                    continue;
                }
            Object length = redisTemplate.opsForHash().get(key, "length");
            if (length == null) {
                continue;
            }
            Map<Object, Object> info = redisTemplate.opsForHash().entries(key);
            String source = String.valueOf(info.getOrDefault("source", ""));
            String version = String.valueOf(info.getOrDefault("version", ""));
            if (!version.isBlank()) {
                String active = activeVersionForSource(source);
                if (!version.equals(active)) continue;
            }
            totalDocs++;
                totalLength += Double.parseDouble(length.toString());
            }
        }
        redisTemplate.opsForValue().set("bm25:stats:total_docs", String.valueOf(totalDocs));
        redisTemplate.opsForValue().set("bm25:stats:avg_doc_length",
                totalDocs > 0 ? String.valueOf(totalLength / totalDocs) : "0");
        Object epoch = redisTemplate.opsForValue().get("rag:knowledge:epoch");
        redisTemplate.opsForValue().set("bm25:stats:knowledge_epoch", epoch == null ? "0" : epoch.toString());
        log.info("BM25 统计信息已重算: totalDocs={}, avgDocLength={}", totalDocs,
                totalDocs > 0 ? String.format("%.2f", totalLength / totalDocs) : "0");
    }

    /** 按来源读取索引时保存的原始文档全文。 */
    /** Historical citation reads use an immutable, published revision rather than today's policy. */
    public Map<String,String> findSourceRevision(String source,String version) {
        if(version==null || version.isBlank()) return findFullSource(source);
        if(source==null || source.isBlank() || version.length()>256) return Map.of();
        String normalized=source.replace('\\','/');
        if(normalized.contains("..") || normalized.startsWith("/") || normalized.contains(":/")) return Map.of();
        List<String> candidates=new ArrayList<>(List.of(normalized));
        if(normalized.startsWith("docs/knowledge/")) candidates.add(normalized.substring("docs/knowledge/".length()));
        for(String candidate:candidates) {
            Map<Object,Object> values;
            if(version.startsWith("legacy:")) {
                values=redisTemplate.opsForHash().entries("bm25:source:"+candidate);
                if(values==null || values.isEmpty() || !String.valueOf(values.getOrDefault("version","")).isBlank()
                        || !version.equals("legacy:"+sha256(String.valueOf(values.getOrDefault("content",""))))) continue;
            } else {
                Double published=redisTemplate.opsForZSet().score("rag:revisions:"+candidate,version);
                if(published==null) continue;
                values=redisTemplate.opsForHash().entries("bm25:source:"+candidate+":"+version);
            }
            if(values==null || values.isEmpty()) continue;
            String scope=String.valueOf(values.getOrDefault("scope","public"));
            if(!scope.isBlank() && !scope.equals("public") && !scope.equals("*")) continue;
            String effective=String.valueOf(values.getOrDefault("effectiveAt",""));
            if(!effective.isBlank() && (parseInstant(effective)==null || parseInstant(effective).isAfter(clock.instant()))) continue;
            Map<String,String> result=new HashMap<>();values.forEach((key,value)->result.put(key.toString(),value.toString()));
            result.put("version",version);return result;
        }
        return Map.of();
    }

    public Map<String, String> findFullSource(String source) {
        if (source == null || source.isBlank()) {
            return Map.of();
        }
        String normalized = source.replace('\\', '/');
        if (normalized.contains("..") || normalized.startsWith("/") || normalized.contains(":/")) {
            return Map.of();
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized);
        if (normalized.startsWith("docs/knowledge/")) {
            candidates.add(normalized.substring("docs/knowledge/".length()));
        }
        for (String candidate : candidates) {
            String activeVersion = activeVersionForSource(candidate);
            Map<Object, Object> values = activeVersion == null ? Map.of()
                    : redisTemplate.opsForHash().entries("bm25:source:" + candidate + ":" + activeVersion);
            if ((values == null || values.isEmpty()) && activeVersion == null)
                values = redisTemplate.opsForHash().entries("bm25:source:" + candidate);
            if (values != null && !values.isEmpty()) {
                Map<String, String> result = new HashMap<>();
                values.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
                result.putIfAbsent("source", normalized);
                return result;
            }
        }
        return Map.of();
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
     * 4. chunkByTableAware —— 表格感知切分，整表不切，适合含表格的文档
     * 5. chunkByCode      —— 代码感知切分，按函数/方法边界切分，适合代码文档
     * 6. chunkSmart       —— 智能切分，自动检测内容类型选择最合适的策略
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

        // ==================== 表格感知切分 ====================

        /** 表格行检测：包含 ｜ 或 | 分隔符，且至少有 2 个分隔符 */
        private static boolean isTableRow(String line) {
            if (line == null) return false;
            long separators = line.chars().filter(c -> c == '|' || c == '｜' || c == '\t').count();
            return separators >= 2;
        }

        /**
         * 表格感知切分：检测到表格时整表不切，保持完整。
         *
         * <p>为什么不能按 512 字符切表格：
         * 表格行之间有对齐关系（表头→数据行），切成两半后：
         * - 后半截没有表头，不知道每列是什么
         * - 同一行的数据可能被切到不同块
         *
         * <p>做法：
         * 1. 逐行扫描，连续的表格行合并为一个"表格块"
         * 2. 非表格行走正常切分（按句子/段落）
         * 3. 表格前后各保留 1 行上下文（帮助理解表格）
         *
         * @param text      原始文本
         * @param chunkSize 非表格部分的切分大小
         * @return 分块列表
         */
        public static List<String> chunkByTableAware(String text, int chunkSize) {
            if (text == null || text.isBlank()) {
                return Collections.emptyList();
            }

            String[] lines = text.split("\\r?\\n");
            List<String> chunks = new ArrayList<>();
            List<String> tableBuffer = new ArrayList<>();
            List<String> textBuffer = new ArrayList<>();
            String prevLine = null;

            for (String line : lines) {
                if (isTableRow(line)) {
                    // 表格行：先 flush 文本缓冲区
                    if (!textBuffer.isEmpty()) {
                        chunks.addAll(chunkBySentence(String.join("\n", textBuffer), chunkSize));
                        textBuffer.clear();
                    }
                    tableBuffer.add(line);
                } else {
                    // 非表格行：flush 表格缓冲区（整表作为一个块）
                    if (!tableBuffer.isEmpty()) {
                        // 表格前后各保留 1 行上下文
                        StringBuilder tableChunk = new StringBuilder();
                        if (prevLine != null && !isTableRow(prevLine)) {
                            tableChunk.append(prevLine).append("\n");
                        }
                        tableChunk.append(String.join("\n", tableBuffer));
                        chunks.add(tableChunk.toString().trim());
                        tableBuffer.clear();
                    }
                    textBuffer.add(line);
                }
                prevLine = line;
            }

            // flush 剩余内容
            if (!tableBuffer.isEmpty()) {
                chunks.add(String.join("\n", tableBuffer).trim());
            }
            if (!textBuffer.isEmpty()) {
                chunks.addAll(chunkBySentence(String.join("\n", textBuffer), chunkSize));
            }

            return chunks.isEmpty() ? List.of(text.trim()) : chunks;
        }

        // ==================== 代码感知切分 ====================

        /** 代码函数/方法边界检测（Java/TypeScript/Python/Go） */
        private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
                "^\\s*(?:" +
                "(?:public|private|protected|static|final|abstract|async|export)?\\s*" +  // 修饰符
                "(?:function|def|func|class|interface|enum|struct|void|int|String|boolean|double|float|long)" +  // 关键字
                "\\s+\\w+" +  // 函数/类名
                "|" +
                "^\\s*(?:@\\w+)" +  // 注解（如 @Test, @Override）
                ")",
                Pattern.MULTILINE
        );

        /**
         * 代码感知切分：按函数/方法边界切分，不切断函数。
         *
         * <p>为什么不能按 512 字符切代码：
         * - 函数可能被切成两半，前半截有签名没体，后半截有体没签名
         * - 切断后 embedding 无法理解这段代码在做什么
         *
         * <p>做法：
         * 1. 检测函数/方法的起始行（通过修饰符+函数名模式）
         * 2. 从函数起始行开始，累积到下一个函数起始行前结束
         * 3. 超长函数（>2*chunkSize）内部再按行号软切
         *
         * @param code     代码文本
         * @param chunkSize 目标块大小
         * @return 分块列表
         */
        public static List<String> chunkByCode(String code, int chunkSize) {
            if (code == null || code.isBlank()) {
                return Collections.emptyList();
            }
            int size = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;

            String[] lines = code.split("\\r?\\n");
            List<String> chunks = new ArrayList<>();
            StringBuilder currentBlock = new StringBuilder();
            boolean inCodeBlock = false;

            for (String line : lines) {
                boolean isBlockStart = CODE_BLOCK_PATTERN.matcher(line).find();

                if (isBlockStart && currentBlock.length() > 0) {
                    // 新函数开始，flush 当前块
                    String block = currentBlock.toString().trim();
                    if (!block.isEmpty()) {
                        // 超长块内部软切
                        if (block.length() > size * 2) {
                            chunks.addAll(chunkByFixedSize(block, size, 0));
                        } else {
                            chunks.add(block);
                        }
                    }
                    currentBlock = new StringBuilder();
                }

                currentBlock.append(line).append('\n');
                inCodeBlock = true;
            }

            // flush 最后一个块
            if (currentBlock.length() > 0) {
                String block = currentBlock.toString().trim();
                if (!block.isEmpty()) {
                    if (block.length() > size * 2) {
                        chunks.addAll(chunkByFixedSize(block, size, 0));
                    } else {
                        chunks.add(block);
                    }
                }
            }

            return chunks.isEmpty() ? List.of(code.trim()) : chunks;
        }

        // ==================== 表格转自然语言 ====================

        /**
         * 表格转自然语言：把表格行转成描述性文字，提升检索命中率。
         *
         * <p>为什么需要：
         * 用户问"iPhone 多少钱"，embedding 搜索"多少钱"可能匹配不到表格里的"8999"。
         * 但转成"iPhone 16 的价格是 8999 元"后，语义就清晰了。
         *
         * <p>做法：
         * 1. 第一行作为表头（列名）
         * 2. 后续每行：列名 + 值 组合成自然语言句子
         * 3. 同时保留原始表格（两份都入索引）
         *
         * @param tableText 表格文本（｜ 分隔）
         * @return 自然语言描述列表（每行一个句子）
         */
        public static List<String> tableToNaturalLanguage(String tableText) {
            if (tableText == null || tableText.isBlank()) {
                return Collections.emptyList();
            }

            String[] lines = tableText.split("\\r?\\n");
            if (lines.length < 2) {
                return List.of(tableText.trim());
            }

            // 解析表头
            String[] headers = lines[0].split("[｜|\\t]");
            for (int i = 0; i < headers.length; i++) {
                headers[i] = headers[i].trim();
            }

            List<String> sentences = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String[] cells = lines[i].split("[｜|\\t]");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < Math.min(headers.length, cells.length); j++) {
                    if (j > 0) sb.append("，");
                    String header = headers[j].isEmpty() ? "列" + (j + 1) : headers[j];
                    sb.append(header).append("为").append(cells[j].trim());
                }
                if (sb.length() > 0) {
                    sentences.add(sb.toString());
                }
            }

            return sentences;
        }

        /**
         * 智能切分：自动检测内容类型，选择最合适的切分策略。
         *
         * <p>优先级：
         * 1. 包含表格行 → 表格感知切分
         * 2. 包含代码特征 → 代码切分
         * 3. 默认 → 句子切分
         */
        public static List<String> chunkSmart(String text, int chunkSize) {
            if (text == null || text.isBlank()) {
                return Collections.emptyList();
            }

            // 检测是否包含表格
            String[] lines = text.split("\\r?\\n");
            long tableLineCount = Arrays.stream(lines).filter(DocumentChunker::isTableRow).count();
            if (tableLineCount >= 2 && tableLineCount > lines.length * 0.2) {
                return chunkByTableAware(text, chunkSize);
            }

            // 检测是否包含代码
            long codeLineCount = Arrays.stream(lines)
                    .filter(l -> l.matches("^\\s*(public|private|protected|function|def|class|interface|import|export|const|let|var).*"))
                    .count();
            if (codeLineCount >= 3 && codeLineCount > lines.length * 0.3) {
                return chunkByCode(text, chunkSize);
            }

            // 默认句子切分
            return chunkBySentence(text, chunkSize);
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
                case "table_aware" -> chunkByTableAware(doc.getContent(), chunkSize);
                case "code" -> chunkByCode(doc.getContent(), chunkSize);
                case "smart" -> chunkSmart(doc.getContent(), chunkSize);
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
                        .version(doc.getVersion()).contentHash(doc.getContentHash())
                        .effectiveAt(doc.getEffectiveAt()).scope(doc.getScope())
                        .retrievalSource(doc.getRetrievalSource())
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
            if (!semanticFeatureEnabled) return extractQueryTermCoverage(query, document);
            try {
                // 通过VectorStore的similaritySearch间接获取query的embedding相似度
                // 使用document内容作为query进行相似度搜索，与原query的embedding对比
                float[] queryEmbedding = featureEmbedding(query);
                float[] docEmbedding = featureEmbedding(document);
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
        return retrieveWithEvidence(query, topK).documents();
    }

    /** 检索结果 + 证据判定：回答前先判断"知识库能不能可靠回答这个问题" */
    public record RetrievalOutcome(List<Document> documents, double topSimilarity,
                                   double topBm25Score, double evidenceScore, boolean weakEvidence,
                                   String route, String reranker, int correctionCount, String knowledgeVersion) {
        public RetrievalOutcome(List<Document> documents, double topSimilarity,
                                double topBm25Score, double evidenceScore, boolean weakEvidence) {
            this(documents, topSimilarity, topBm25Score, evidenceScore, weakEvidence,
                    "hybrid", "feature", 0, "unknown");
        }
    }

    /**
     * 带证据判定的混合检索（Adaptive RAG / CRAG 思路的本地实现）：
     * 1. 召回池扩大为 2×topK（与离线评测链路一致）：先广召回、再精排截断，
     *    避免"只召回 3 条就精排"把漏检固定在召回阶段；
     * 2. 记录 top-1 的原始语义相似度（Milvus cosine）与 BM25 得分，形成"证据强度"；
     * 3. 证据不足（相似度低且无 BM25 强命中）时调用方可直接拒答转人工——
     *    实测小模型拿到弱相关上下文时会把无关资料"聊成"答案，这一步从机制上掐断。
     */
    public RetrievalOutcome retrieveWithEvidence(String query, int topK) {
        return AgentTelemetry.observed("retrieve", () -> retrieveObserved(query,topK));
    }

    private RetrievalOutcome retrieveObserved(String query, int topK) {
        long rewriteStarted = System.nanoTime();
        boolean decomposed = query != null && queryDecomposer.hasMultipleIntents(query);
        List<String> recallQueries;
        int correctionCount;
        if (decomposed) {
            // One decomposition attempt replaces dictionary correction for this request; never stack both.
            recallQueries = queryDecomposer.decompose(query);
            correctionCount = 1;
            if (recallQueries == null || recallQueries.isEmpty()) recallQueries = List.of(query);
        } else {
            String expanded = RetrievalQueryRewriter.expand(query);
            correctionCount = expanded != null && !expanded.equals(query == null ? null : query.trim()) ? 1 : 0;
            recallQueries = List.of(expanded == null ? "" : expanded);
        }
        AgentTelemetry.recordStage("rewrite", (System.nanoTime() - rewriteStarted) / 1_000_000,
                correctionCount > 0 ? "success" : "miss");
        String route = resolveRoute(query, decomposed);
        // Capture a stable Redis epoch after the one allowed query transformation. Reruns reuse these queries
        // and never trigger another dictionary or LLM correction attempt.
        String snapshot = currentKnowledgeVersion();
        RetrievalOutcome outcome = retrieveSnapshot(query, topK, recallQueries, route, correctionCount, snapshot);
        String after = currentKnowledgeVersion();
        if (!snapshot.equals(after)) {
            snapshot = after;
            outcome = retrieveSnapshot(query, topK, recallQueries, route, correctionCount, snapshot);
            if (!snapshot.equals(currentKnowledgeVersion())) {
                return new RetrievalOutcome(List.of(), 0.0, 0.0, 0.0, true, route,
                        outcome.reranker(), correctionCount, snapshot);
            }
        }
        return outcome;
    }

    private RetrievalOutcome retrieveSnapshot(String query, int topK, List<String> recallQueries,
                                                String route, int correctionCount, String knowledgeVersion) {
        long retrievalStarted = System.nanoTime();
        int recallSize = Math.max(topK * 2, 6);
        List<RetrievedDocument> vectorResults = new ArrayList<>();
        List<RetrievedDocument> keywordResults = new ArrayList<>();
        Map<String, Double> similarityById = new HashMap<>();
        Map<String, Double> bm25ById = new HashMap<>();
        Map<String, Double> bm25BySource = new HashMap<>();
        double topBm25Score = 0.0;
        for (String recallQuery : recallQueries) {
            List<RetrievedDocument> vectors = "exact".equals(route)
                    ? new ArrayList<>() : milvusVectorRetrieve(recallQuery, recallSize);
            List<RetrievedDocument> keywords = "semantic".equals(route)
                    ? new ArrayList<>() : bm25KeywordRetrieve(recallQuery, recallSize);
            if (vectors.isEmpty() && keywords.isEmpty()) {
                if (vectorFallbackEnabled && !"semantic".equals(route)) vectors = milvusVectorRetrieve(recallQuery, recallSize);
                if (!"exact".equals(route)) keywords = bm25KeywordRetrieve(recallQuery, recallSize);
            }
            for (RetrievedDocument doc : vectors) similarityById.merge(doc.getId(), doc.getScore(), Math::max);
            if (!keywords.isEmpty()) topBm25Score = Math.max(topBm25Score, keywords.get(0).getScore());
            for (RetrievedDocument doc : keywords) bm25ById.merge(doc.getId(), doc.getScore(), Math::max);
            for (RetrievedDocument doc : keywords) bm25BySource.merge(doc.getSource(), doc.getScore(), Math::max);
            vectorResults.addAll(vectors);
            keywordResults.addAll(keywords);
        }

        int graphRank = 1;
        for (Document graphDocument : GraphEvidenceContext.currentDocuments()) {
            if (graphDocument == null || graphDocument.getContent() == null || graphDocument.getContent().isBlank()
                    || !isCurrentlyApplicable(graphDocument.getSource(), graphDocument.getVersion(),
                    graphDocument.getEffectiveAt(), graphDocument.getScope())) continue;
            RetrievedDocument candidate = RetrievedDocument.builder().id(graphDocument.getId())
                    .content(graphDocument.getContent()).source(graphDocument.getSource()).type(graphDocument.getType())
                    .version(graphDocument.getVersion()).contentHash(graphDocument.getContentHash())
                    .effectiveAt(graphDocument.getEffectiveAt()).scope(graphDocument.getScope())
                    .retrievalSource("graph").rank(graphRank++).score(0.0).build();
            vectorResults.add(candidate);
        }

        List<FusedDocument> fusedResults = rrfFusion(vectorResults, keywordResults);
        RerankSelection rerankSelection = rerankCandidates(query, fusedResults, topK);
        List<Document> documents = rerankSelection.documents().stream()
                .map(r -> Document.builder().id(r.getId()).content(r.getContent()).source(r.getSource()).type(r.getType())
                        .score(r.getRerankScore()).retrievalSource(r.getRetrievalSource() == null ? route : r.getRetrievalSource())
                        .version(r.getVersion()).contentHash(r.getContentHash())
                        .effectiveAt(r.getEffectiveAt()).scope(r.getScope()).knowledgeVersion(knowledgeVersion)
                        .evidenceVerified(r.getVersion() != null && !r.getVersion().isBlank()
                                && r.getContentHash() != null && !r.getContentHash().isBlank()
                                && isCurrentlyApplicable(r.getSource(), r.getVersion(), r.getEffectiveAt(), r.getScope()))
                        .build())
                .filter(Document::isEvidenceVerified).toList();

        double topSimilarity = documents.isEmpty() ? 0.0
                : documents.stream().mapToDouble(d -> similarityById.getOrDefault(d.getId(), 0.0)).max().orElse(0.0);
        double returnedBm25Score = documents.stream().mapToDouble(d -> Math.max(bm25ById.getOrDefault(d.getId(), 0.0),
                "graph".equals(d.getRetrievalSource()) ? bm25BySource.getOrDefault(d.getSource(), 0.0) : 0.0)).max().orElse(0.0);
        boolean bm25Strong = returnedBm25Score >= BM25_STRONG_SCORE;
        double evidenceScore = bm25Strong ? Math.max(topSimilarity, 0.60) : topSimilarity;
        boolean weakEvidence = documents.isEmpty() || evidenceScore < evidenceThreshold;
        AgentTelemetry.recordStage("retrieve", (System.nanoTime() - retrievalStarted) / 1_000_000,
                weakEvidence ? "refusal" : "success");
        return new RetrievalOutcome(documents, topSimilarity, returnedBm25Score, evidenceScore, weakEvidence,
                route, rerankSelection.name(), correctionCount, knowledgeVersion);
    }

    record RerankSelection(List<RerankedDocument> documents, String name) { }

    RerankSelection rerankCandidates(String query, List<FusedDocument> candidates, int topK) {
        long started = System.nanoTime();
        List<RerankedDocument> featureRanked = featureRerankerEnabled
                ? new CrossEncoderReranker().rerank(query, candidates, candidates.size())
                : candidates.stream().map(d -> RerankedDocument.builder().id(d.getId()).content(d.getContent())
                        .source(d.getSource()).type(d.getType()).rrfScore(d.getRrfScore()).version(d.getVersion())
                        .contentHash(d.getContentHash()).effectiveAt(d.getEffectiveAt()).scope(d.getScope())
                        .retrievalSource(d.getRetrievalSource()).rerankScore(d.getRrfScore()).build())
                        .sorted(Comparator.comparingDouble(RerankedDocument::getRerankScore).reversed()).toList();
        String fallbackName = featureRerankerEnabled ? "feature" : "recall-order";
        if (!neuralRerankerEnabled || neuralReranker == null || candidates.isEmpty()) {
            AgentTelemetry.recordStage("rerank", (System.nanoTime() - started) / 1_000_000, "success");
            return new RerankSelection(featureRanked.stream().limit(topK).toList(), fallbackName);
        }
        try {
            List<Double> scores = neuralReranker.score(query, candidates.stream().map(FusedDocument::getContent).toList());
            if (scores.size() != candidates.size() || scores.stream().anyMatch(score -> score == null || !Double.isFinite(score)))
                throw new IllegalStateException("Local reranker returned invalid scores");
            List<RerankedDocument> ranked = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                FusedDocument d = candidates.get(i);
                ranked.add(RerankedDocument.builder().id(d.getId()).content(d.getContent()).source(d.getSource())
                        .type(d.getType()).rrfScore(d.getRrfScore()).version(d.getVersion()).contentHash(d.getContentHash())
                        .effectiveAt(d.getEffectiveAt()).scope(d.getScope()).retrievalSource(d.getRetrievalSource())
                        .rerankScore(scores.get(i)).build());
            }
            AgentTelemetry.recordStage("rerank", (System.nanoTime() - started) / 1_000_000, "success");
            return new RerankSelection(ranked.stream().sorted(Comparator.comparingDouble(RerankedDocument::getRerankScore).reversed())
                    .limit(topK).toList(), "qwen3-reranker-0.6b");
        } catch (Exception e) {
            log.warn("Local neural reranker failed; using feature reranker: {}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            AgentTelemetry.recordStage("rerank", (System.nanoTime() - started) / 1_000_000, "fallback");
            return new RerankSelection(featureRanked.stream().limit(topK).toList(), fallbackName);
        }
    }

    private String resolveRoute(String query, boolean complex) {
        String configured = retrievalStrategy == null ? "auto" : retrievalStrategy.trim().toLowerCase();
        if (Set.of("semantic", "exact", "hybrid").contains(configured)) return configured;
        if (query != null && query.matches(".*(?:SKU|型号|型号是|iPhone|Galaxy|Pixel)[：:#\\s-]*[A-Za-z0-9-]{1,}.*")) return "exact";
        return complex ? "hybrid" : "semantic";
    }

    private String currentKnowledgeVersion() {
        try {
            refreshCurrentRevisions();
            Object epoch = redisTemplate.opsForValue().get("rag:knowledge:epoch");
            String version = epoch == null ? "0" : epoch.toString();
            if (semanticAnswerCacheService != null) semanticAnswerCacheService.observeKnowledgeVersion(version);
            return version;
        } catch (Exception ignored) { return "unknown"; }
    }

    /**
     * 带问题分解的混合检索：识别多意图 → 拆分子问题 → 分别检索 → 合并去重。
     *
     * <p>为什么需要分解：
     * 用户问"退货要多久能退款？运费谁出？"，如果不分解，混合检索只能
     * 命中"退货"相关的文档，"运费"相关的可能排在后面甚至丢失。
     * 分解后分别检索，每个子问题都能精准命中对应文档，合并后覆盖面更广。
     *
     * <p>合并策略：
     * - 每个子问题检索 topK 个结果
     * - 合并后按 RRF 分数重新排序，去重取前 topK
     * - 保留子问题来源信息，便于溯源
     *
     * @param query 用户原始 query
     * @param topK  最终返回的文档数量
     * @return 合并去重后的 topK 个最相关文档
     */
    public List<Document> retrieveWithDecomposition(String query, int topK) {
        List<String> subQueries = queryDecomposer.decompose(query);

        // 单意图，直接走原有检索
        if (subQueries.size() <= 1) {
            return retrieve(query, topK);
        }

        log.info("多意图检索: 原始='{}', 子问题={}", query, subQueries);

        // 分别检索，每个子问题取 topK
        Map<String, Document> merged = new LinkedHashMap<>();
        Map<String, Double> scoreMap = new HashMap<>();

        for (int i = 0; i < subQueries.size(); i++) {
            String subQuery = subQueries.get(i);
            List<Document> subResults = retrieve(subQuery, topK);

            for (int j = 0; j < subResults.size(); j++) {
                Document doc = subResults.get(j);
                // RRF 合并：用子问题内的排名贡献分数
                double rrfScore = 1.0 / (RRF_K + j + 1);
                scoreMap.merge(doc.getId(), rrfScore, Double::sum);
                merged.putIfAbsent(doc.getId(), doc);
            }

            log.info("子问题 '{}' 检索到 {} 条结果", subQuery, subResults.size());
        }

        // 按合并后的 RRF 分数排序，取 topK
        List<Document> result = merged.entrySet().stream()
                .sorted((a, b) -> Double.compare(
                        scoreMap.getOrDefault(b.getKey(), 0.0),
                        scoreMap.getOrDefault(a.getKey(), 0.0)))
                .limit(topK)
                .map(entry -> {
                    Document doc = entry.getValue();
                    // 追加子问题来源信息（创建新对象，不修改原对象）
                    return Document.builder()
                            .id(doc.getId())
                            .content(doc.getContent())
                            .source(doc.getSource() + " [decomposed]")
                            .type(doc.getType())
                            .keywords(doc.getKeywords())
                            .embedding(doc.getEmbedding())
                            .build();
                })
                .toList();

        log.info("多意图检索完成: {} 个子问题, 合并后返回 {} 条结果", subQueries.size(), result.size());
        return result;
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
                        .id(toVectorId(chunk.getChunkId()))
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
                3. 如果参考资料只是提到了相关关键词、但并不能直接完整回答问题，也按"无法回答"处理：
                   明确回复"抱歉，我在知识库中没有找到相关信息，建议您联系人工客服进一步确认"，
                   不得进行推测、类比或补充常识性建议，也不得通过"资料未列出即为不支持"的方式推断结论。
                   但如果资料已经说明了处理流程、只是需要用户补充信息（例如订单编号），
                   则应正常说明流程并请用户补充该信息，不要按"无法回答"拒答。
                4. 不要输出任何思考过程或内部指令，回答中不要提及"搜索结果""检索""知识库"等系统内部概念。

                参考资料：
                %s

                用户问题：%s

                请用简洁友好的语气回答。
                """, context.toString(), sanitizedQuery);

        try {
            return agentLlmClient.chat(prompt);
        } catch (Exception e) {
            log.error("生成答案失败: {}", e.getMessage(), e);
            return "抱歉，暂时无法回答您的问题，请稍后再试。";
        }
    }
}
