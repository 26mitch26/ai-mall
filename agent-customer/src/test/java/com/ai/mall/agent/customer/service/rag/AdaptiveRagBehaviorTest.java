package com.ai.mall.agent.customer.service.rag;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.llm.AgentLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdaptiveRagBehaviorTest {
    @Test void chunkStrategyChangeCreatesNewVersionWithoutChangingSourceHash() {
        var redis=mock(StringRedisTemplate.class,RETURNS_DEEP_STUBS);
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);
        var vector=mock(VectorStore.class);
        var rag=service(vector,redis);
        var whole=Document.builder().id("policy").source("policy.md").type("policy").content("# 退货\n支持七日内退货。\n但定制商品除外。").build();
        var section=whole.toBuilder().build();
        rag.indexDocuments(List.of(whole));
        rag.indexDocuments(List.of(section),"policy_section");
        assertEquals(whole.getContentHash(),section.getContentHash());
        assertNotEquals(whole.getVersion(),section.getVersion());
    }
    @Test void publicRefundProcedureExpansionPreservesQuestionAndPrivateProgressBoundary() {
        String query = "我不知道如何才能拿到退款";
        String expanded = RetrievalQueryRewriter.expand(query);
        assertTrue(expanded.startsWith(query));
        assertTrue(expanded.contains("操作路径"));
        assertTrue(expanded.contains("选择订单"));
        assertFalse(RetrievalQueryRewriter.expand("如何查询我的退款进度").contains("操作路径"));
    }
    @Test void explicitFeatureOptOutPreservesFusedRecallOrder() throws Exception {
        RagService rag = service(null, null);
        set(rag, "featureRerankerEnabled", false);
        var first = RagService.FusedDocument.builder().id("refund").source("refund.md").content("退款到账规则")
                .rrfScore(.2).build();
        var second = RagService.FusedDocument.builder().id("payment").source("payment.md").content("其他支付规则")
                .rrfScore(.01).build();
        var result = rag.rerankCandidates("退款多久到账？", List.of(second, first), 1);
        assertEquals("recall-order", result.name());
        assertEquals("refund", result.documents().get(0).getId());
    }

    @Test
    void queryDictionaryExpansionRunsOnceAndKeepsTheOriginalQuestion() {
        String expanded = RetrievalQueryRewriter.expand("不想要了怎么办");
        assertTrue(expanded.startsWith("不想要了怎么办"));
        assertEquals(1, expanded.split("退款", -1).length - 1);
    }

    @Test
    void adaptiveRouteSelectsExactForOrderIdentifiersAndKeepsExplicitModes() throws Exception {
        RagService rag = service(null, null);
        Method resolve = RagService.class.getDeclaredMethod("resolveRoute", String.class, boolean.class);
        resolve.setAccessible(true);
        assertEquals("exact", resolve.invoke(rag, "SKU: ABC-123", false));
        assertEquals("semantic", resolve.invoke(rag, "退货运费怎么计算？", false));
        assertEquals("hybrid", resolve.invoke(rag, "退货？运费谁承担？", true));
        set(rag, "retrievalStrategy", "semantic");
        assertEquals("semantic", resolve.invoke(rag, "订单号 SF12345678", true));
    }

    @Test
    void neuralRerankerTimeoutFallsBackToFeatureScoring() throws Exception {
        RagService rag = service(null, null);
        set(rag, "neuralRerankerEnabled", true);
        set(rag, "neuralReranker", (NeuralReranker) (query, passages) -> { throw new java.net.http.HttpTimeoutException("timeout"); });
        RagService.FusedDocument candidate = RagService.FusedDocument.builder().id("a")
                .content("签收后七日内可以申请退货退款").source("refund.md").type("policy").rrfScore(0.02).build();

        RagService.RerankSelection result = rag.rerankCandidates("如何退货", List.of(candidate), 1);
        assertEquals("feature", result.name());
        assertEquals(1, result.documents().size());
    }

    @Test
    void decompositionUsesAtMostOneLlmCallAndReturnsAtMostThreeQueries() {
        AgentLlmClient llm = mock(AgentLlmClient.class);
        when(llm.chat(anyString())).thenReturn("1. 退货多久能退款？\n2. 运费由谁承担？\n3. 退款退到哪里？\n4. 多余问题？");
        QueryDecomposer decomposer = new QueryDecomposer(llm);

        List<String> queries = decomposer.decompose("退货多久退款？运费谁承担？");
        assertEquals(3, queries.size());
        verify(llm, times(1)).chat(anyString());
        decomposer.decompose("退款多久到账");
        verifyNoMoreInteractions(llm);
    }

    @Test
    void knowledgeCacheInvalidatesImmediatelyOnPublishedRevision() {
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embedding.embed(anyString())).thenReturn(new float[]{1, 0});
        SemanticAnswerCacheService cache = new SemanticAnswerCacheService(embedding, true);
        cache.store("退货政策", "", "七天内可退", List.of(Document.builder().version("v1").build()));
        assertTrue(cache.lookup("退货政策", "").isPresent());

        cache.invalidateKnowledgeVersion();
        assertTrue(cache.lookup("退货政策", "").isEmpty());
    }

    @Test
    void failedVectorPublishLeavesThePreviouslyActiveVersionUntouched() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForValue().get("rag:active:refund.md")).thenReturn("previous-version");
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new IllegalStateException("milvus unavailable")).when(vectorStore).add(anyList());
        RagService rag = service(vectorStore, redis);

        Document incoming = Document.builder().id("refund-doc").source("refund.md").type("policy")
                .content("新版本：退款需要三个工作日").build();
        assertThrows(IllegalStateException.class, () -> rag.indexDocuments(List.of(incoming)));

        verify(redis.opsForValue(), never()).set(eq("rag:active:refund.md"), anyString());
        verify(redis.opsForValue(), never()).increment(eq("rag:knowledge:epoch"));
    }

    @Test
    void futureRevisionKeepsCurrentOneUntilInjectedClockCrossesEffectiveTime() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForZSet().zCard("rag:revisions:refund.md")).thenReturn(2L);
        when(redis.opsForZSet().reverseRangeByScore(eq("rag:revisions:refund.md"), anyDouble(), anyDouble()))
                .thenReturn(Set.of("old-version"), Set.of("new-version"));
        when(redis.opsForValue().get("rag:active:refund.md:public")).thenReturn("old-version");
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        RagService rag = service(null, redis);
        set(rag, "clock", Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));

        assertEquals("old-version", rag.activeVersionForSource("refund.md"));
        set(rag, "clock", Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC));

        assertEquals("new-version", rag.activeVersionForSource("refund.md"));
        verify(redis).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void citationReadsArchivedPublishedVersionAndRejectsUnknownOrPrivateRevisions() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class,RETURNS_DEEP_STUBS);
        when(redis.opsForZSet().score("rag:revisions:refund.md","old-version")).thenReturn(1.0);
        Map<Object,Object> old=Map.of("source","refund.md","version","old-version","scope","public","content","旧政策：退货运费8元。","contentHash","old-hash");
        when(redis.opsForHash().entries("bm25:source:refund.md:old-version")).thenReturn(old);
        RagService rag=service(null,redis);
        assertEquals("旧政策：退货运费8元。",rag.findSourceRevision("docs/knowledge/refund.md","old-version").get("content"));
        assertTrue(rag.findSourceRevision("refund.md","unpublished-version").isEmpty());
        when(redis.opsForZSet().score("rag:revisions:refund.md","private-version")).thenReturn(1.0);
        when(redis.opsForHash().entries("bm25:source:refund.md:private-version")).thenReturn(Map.of("scope","private","content","private policy"));
        assertTrue(rag.findSourceRevision("refund.md","private-version").isEmpty());
    }

    @Test
    void publishedRevisionSuppressesLegacyBm25RowsAndDuplicateSourceListing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForSet().members("rag:revision:sources")).thenReturn(Set.of("refund.md"));
        when(redis.opsForSet().members("bm25:inverted:退货")).thenReturn(Set.of("legacy-chunk", "active-chunk"));
        when(redis.opsForValue().get("rag:knowledge:epoch")).thenReturn("2");
        when(redis.opsForValue().get("bm25:stats:knowledge_epoch")).thenReturn("2");
        when(redis.opsForValue().get("bm25:stats:total_docs")).thenReturn("2");
        when(redis.opsForValue().get("bm25:stats:avg_doc_length")).thenReturn("2");
        when(redis.opsForHash().get("bm25:doc:legacy-chunk:tf", "退货")).thenReturn("1");
        when(redis.opsForHash().get("bm25:doc:active-chunk:tf", "退货")).thenReturn("1");
        when(redis.opsForHash().get("bm25:doc:legacy-chunk", "length")).thenReturn("2");
        when(redis.opsForHash().get("bm25:doc:active-chunk", "length")).thenReturn("2");
        when(redis.opsForHash().entries("bm25:doc:legacy-chunk")).thenReturn(Map.of(
                "content", "旧版退货条款", "source", "refund.md", "type", "policy", "length", "2",
                "version", "", "contentHash", "", "scope", "public"));
        when(redis.opsForHash().entries("bm25:doc:active-chunk")).thenReturn(Map.of(
                "content", "新版退货条款", "source", "refund.md", "type", "policy", "length", "2",
                "version", "v2", "contentHash", "hash-v2", "scope", "public"));
        when(redis.opsForZSet().zCard("rag:revisions:refund.md")).thenReturn(1L);
        when(redis.opsForZSet().reverseRangeByScore(eq("rag:revisions:refund.md"), anyDouble(), anyDouble()))
                .thenReturn(Set.of("v2"));
        when(redis.opsForHash().get("bm25:source:refund.md:v2", "scope")).thenReturn("public");
        when(redis.opsForValue().get("rag:active:refund.md:public")).thenReturn("v2");

        RagService rag = service(null, redis);
        List<RagService.RetrievedDocument> results = rag.bm25KeywordRetrieve("退货", 5);

        assertEquals(List.of("active-chunk"), results.stream().map(RagService.RetrievedDocument::getId).toList());

        when(redis.keys("bm25:source:*")).thenReturn(Set.of("bm25:source:refund.md", "bm25:source:refund.md:v2"));
        when(redis.opsForHash().entries("bm25:source:refund.md")).thenReturn(Map.of(
                "source", "refund.md", "type", "policy", "content", "旧版全文", "version", "", "scope", "public"));
        when(redis.opsForHash().entries("bm25:source:refund.md:v2")).thenReturn(Map.of(
                "source", "refund.md", "type", "policy", "content", "# 新版退货政策", "version", "v2",
                "contentHash", "hash-v2", "scope", "public"));
        assertEquals(List.of("v2"), rag.listIndexedSources().stream().map(document -> document.get("version")).toList());
    }

    @Test
    void futureStagedRevisionDoesNotExposeLegacyRowsBeforeEffectiveTime() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForSet().members("rag:revision:sources")).thenReturn(Set.of("refund.md"));
        when(redis.opsForSet().members("bm25:inverted:退货")).thenReturn(Set.of("legacy-chunk"));
        when(redis.opsForValue().get("rag:knowledge:epoch")).thenReturn("2");
        when(redis.opsForValue().get("bm25:stats:knowledge_epoch")).thenReturn("2");
        when(redis.opsForValue().get("bm25:stats:total_docs")).thenReturn("1");
        when(redis.opsForValue().get("bm25:stats:avg_doc_length")).thenReturn("2");
        when(redis.opsForHash().get("bm25:doc:legacy-chunk:tf", "退货")).thenReturn("1");
        when(redis.opsForHash().get("bm25:doc:legacy-chunk", "length")).thenReturn("2");
        when(redis.opsForHash().entries("bm25:doc:legacy-chunk")).thenReturn(Map.of(
                "content", "旧版退货条款", "source", "refund.md", "type", "policy", "length", "2",
                "version", "", "contentHash", "", "scope", "public"));
        // The revision is registered but has no effective revision at the current clock time.
        when(redis.opsForZSet().zCard("rag:revisions:refund.md")).thenReturn(1L);
        when(redis.opsForZSet().reverseRangeByScore(eq("rag:revisions:refund.md"), anyDouble(), anyDouble()))
                .thenReturn(Set.of());

        RagService rag = service(null, redis);
        assertTrue(rag.bm25KeywordRetrieve("退货", 5).isEmpty());
        verify(redis.opsForZSet(), never()).reverseRangeByScore(eq("rag:revisions:refund.md"), anyDouble(), anyDouble());
    }

    @Test
    void publishedRevisionSuppressesLegacyMilvusRowsEvenWhenMetadataFilterIsIgnored() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForSet().members("rag:revision:sources")).thenReturn(Set.of("refund.md"));
        when(redis.opsForZSet().zCard("rag:revisions:refund.md")).thenReturn(1L);
        when(redis.opsForZSet().reverseRangeByScore(eq("rag:revisions:refund.md"), anyDouble(), anyDouble()))
                .thenReturn(Set.of("v2"));
        when(redis.opsForHash().get("bm25:source:refund.md:v2", "scope")).thenReturn("public");
        when(redis.opsForValue().get("rag:active:refund.md:public")).thenReturn("v2");
        when(redis.opsForHash().entries("bm25:source:refund.md:v2")).thenReturn(Map.of(
                "source", "refund.md", "type", "policy", "content", "当前签收后七日内可退货",
                "version", "v2", "contentHash", "hash-v2", "scope", "public"));
        when(redis.keys("bm25:source:*")).thenReturn(Set.of("bm25:source:refund.md:v2"));

        VectorStore vectorStore = mock(VectorStore.class);
        org.springframework.ai.document.Document oldVector = new org.springframework.ai.document.Document(
                "old-vector", "旧政策向量内容", Map.of("source", "refund.md", "type", "policy",
                "version", "", "contentHash", "", "scope", "public"));
        org.springframework.ai.document.Document activeVector = new org.springframework.ai.document.Document(
                "active-vector", "当前签收后七日内可退货", Map.of("source", "refund.md", "type", "policy",
                "version", "v2", "contentHash", "hash-v2", "scope", "public"));
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(oldVector, activeVector));

        List<RagService.RetrievedDocument> results = service(vectorStore, redis).milvusVectorRetrieve("退货", 5);

        assertEquals(List.of("active-vector"), results.stream().map(RagService.RetrievedDocument::getId).toList());
    }

    @Test
    void futureStagedRevisionCannotBeBypassedByLegacyVectorEnrichment() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForSet().members("rag:revision:sources")).thenReturn(Set.of("refund.md"));
        when(redis.opsForZSet().zCard("rag:revisions:refund.md")).thenReturn(1L);
        when(redis.opsForZSet().reverseRangeByScore(eq("rag:revisions:refund.md"), anyDouble(), anyDouble()))
                .thenReturn(Set.of());
        when(redis.keys("bm25:source:*")).thenReturn(Set.of("bm25:source:refund.md"));
        String legacyText = "签收后七日内可以申请退货";
        when(redis.opsForHash().entries("bm25:source:refund.md")).thenReturn(Map.of(
                "source", "refund.md", "type", "policy", "content", legacyText, "version", "", "scope", "public"));
        when(redis.opsForValue().get("rag:active:refund.md")).thenReturn(null);

        VectorStore vectorStore = mock(VectorStore.class);
        org.springframework.ai.document.Document legacyVector = new org.springframework.ai.document.Document(
                "legacy-vector", legacyText, Map.of("source", "refund.md", "type", "policy",
                "version", "", "contentHash", "", "scope", "public"));
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(legacyVector));

        assertTrue(service(vectorStore, redis).milvusVectorRetrieve("退货", 5).isEmpty());
    }

    @Test
    void unregisteredLegacyBm25DocumentStillHasCitableSnapshot() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForSet().members("rag:revision:sources")).thenReturn(Set.of());
        when(redis.opsForSet().members("bm25:inverted:退货")).thenReturn(Set.of("legacy-chunk"));
        when(redis.opsForValue().get("rag:knowledge:epoch")).thenReturn("0");
        when(redis.opsForValue().get("bm25:stats:knowledge_epoch")).thenReturn("0");
        when(redis.opsForValue().get("bm25:stats:total_docs")).thenReturn("1");
        when(redis.opsForValue().get("bm25:stats:avg_doc_length")).thenReturn("2");
        when(redis.opsForHash().get("bm25:doc:legacy-chunk:tf", "退货")).thenReturn("1");
        when(redis.opsForHash().get("bm25:doc:legacy-chunk", "length")).thenReturn("2");
        String fullText = "旧版签收后七日内可申请退货";
        when(redis.opsForHash().entries("bm25:doc:legacy-chunk")).thenReturn(Map.of(
                "content", "旧版签收后七日内可申请退货", "source", "legacy-refund.md", "type", "policy",
                "length", "2", "version", "", "contentHash", "", "scope", "public"));
        when(redis.opsForZSet().zCard("rag:revisions:legacy-refund.md")).thenReturn(0L);
        when(redis.opsForValue().get("rag:active:legacy-refund.md")).thenReturn(null);
        when(redis.opsForHash().entries("bm25:source:legacy-refund.md")).thenReturn(Map.of(
                "source", "legacy-refund.md", "type", "policy", "content", fullText, "version", "", "scope", "public"));

        RagService rag = service(null, redis);
        List<RagService.RetrievedDocument> results = rag.bm25KeywordRetrieve("退货", 5);

        assertEquals(1, results.size());
        assertTrue(results.get(0).getVersion().startsWith("legacy:"));
        assertNotNull(results.get(0).getContentHash());
        assertEquals(fullText, rag.findSourceRevision("legacy-refund.md", results.get(0).getVersion()).get("content"));
    }

    private static RagService service(VectorStore store, StringRedisTemplate redis) {
        return new RagService(null, null, store, redis, null, null);
    }

    @Test void frequentTermsHaveFinitePositiveBm25Weight() {
        StringRedisTemplate redis = bm25PublishedFixture(false);
        var results = service(null, redis).bm25KeywordRetrieve("退款", 3);
        assertEquals(1, results.size());
        assertTrue(Double.isFinite(results.get(0).getScore()));
        assertTrue(results.get(0).getScore() > 0);
    }

    @Test void archivedVersionsDoNotInflateBm25DocumentFrequency() {
        var clean = service(null, bm25PublishedFixture(false)).bm25KeywordRetrieve("退款", 3);
        var archived = service(null, bm25PublishedFixture(true)).bm25KeywordRetrieve("退款", 3);
        assertEquals(List.of("active"), archived.stream().map(RagService.RetrievedDocument::getId).toList());
        assertEquals(clean.get(0).getScore(), archived.get(0).getScore(), 1e-9);
    }

    private static StringRedisTemplate bm25PublishedFixture(boolean includeArchived) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForSet().members("rag:revision:sources")).thenReturn(Set.of("refund.md"));
        when(redis.opsForSet().members("bm25:inverted:退款")).thenReturn(includeArchived ? Set.of("active", "archived") : Set.of("active"));
        when(redis.opsForValue().get("rag:knowledge:epoch")).thenReturn("1");
        when(redis.opsForValue().get("bm25:stats:knowledge_epoch")).thenReturn("1");
        when(redis.opsForValue().get("bm25:stats:total_docs")).thenReturn("1");
        when(redis.opsForValue().get("bm25:stats:avg_doc_length")).thenReturn("2");
        when(redis.opsForZSet().zCard("rag:revisions:refund.md")).thenReturn(1L);
        when(redis.opsForZSet().reverseRangeByScore(eq("rag:revisions:refund.md"), anyDouble(), anyDouble())).thenReturn(Set.of("v1"));
        when(redis.opsForHash().get("bm25:source:refund.md:v1", "scope")).thenReturn("public");
        when(redis.opsForValue().get("rag:active:refund.md:public")).thenReturn("v1");
        when(redis.opsForHash().entries("bm25:doc:active")).thenReturn(Map.of("content", "退款", "source", "refund.md", "version", "v1", "scope", "public"));
        when(redis.opsForHash().entries("bm25:doc:archived")).thenReturn(Map.of("content", "退款", "source", "refund.md", "version", "v0", "scope", "public"));
        when(redis.opsForHash().get("bm25:doc:active:tf", "退款")).thenReturn("1");
        when(redis.opsForHash().get("bm25:doc:active", "length")).thenReturn("2");
        return redis;
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
