package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.rag.RagService;
import com.ai.mall.agent.customer.service.rag.SemanticAnswerCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库管理接口。
 * <p>
 * 全链路压测前的必备能力：此前客服链路压测时知识库为空、服务没有文档导入端点，
 * 只能测到"输入净化 → Milvus 检索 → 拒答护栏"（空库直接拒答，不触达 LLM 生成路径）。
 * 补齐本接口后，可先灌入退货/运费/支付等政策文档，再压"检索命中 → LLM 生成"的生产主路径。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库文档导入接口")
public class KnowledgeController {

    private final RagService ragService;
    private final SemanticAnswerCacheService semanticAnswerCacheService;

    @Value("${ai.model.llm.model:qwen3.5-noVL:latest}")
    private String chatModel;

    @Value("${spring.ai.openai.embedding.options.model:bge-m3}")
    private String embeddingModel;

    @Value("${ai.model.llm.base-url:http://localhost:11434/api/chat}")
    private String llmBaseUrl;

    @Value("${ai.rag.reranker.enabled:false}")
    private boolean neuralRerankerEnabled;

    @Value("${agent.knowledge.admin-token:}")
    private String knowledgeAdminToken = "";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IngestRequest {
        /** 文档正文（支持多段落长文本），必填 */
        private String content;
        /** 文档来源标识，如 refund-policy.md；缺省自动生成 */
        private String source;
        /** 文档类型：policy / manual / faq 等；缺省 general */
        private String type;
        /** 分块策略：fixed_size / sentence / semantic；缺省 null 表示不分块整体索引 */
        private String chunkStrategy;
        /** Optional human revision label; immutable internal version is derived with the content hash. */
        private String version;
        /** Inclusive activation time; omitted means effective immediately. */
        private java.time.Instant effectiveAt;
        /** Audience scope; defaults to public. */
        private String scope;
    }

    @PostMapping("/ingest")
    @Operation(summary = "导入知识库文档", description = "单篇文档入库：构建 BM25 倒排索引并将文档（按需分块后）写入 Milvus 向量库")
    public Map<String, Object> ingest(@RequestBody IngestRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value="X-Knowledge-Admin-Token", required=false) String adminToken) {
        if (knowledgeAdminToken == null || knowledgeAdminToken.isBlank() || adminToken == null
                || !java.security.MessageDigest.isEqual(knowledgeAdminToken.getBytes(StandardCharsets.UTF_8),
                    adminToken.getBytes(StandardCharsets.UTF_8))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Knowledge write access denied");
        }
        Map<String, Object> result = new HashMap<>();
        if (request.getContent() == null || request.getContent().isBlank()) {
            result.put("indexedChunks", 0);
            result.put("docId", null);
            return result;
        }
        String docId = request.getSource() != null && !request.getSource().isBlank()
                ? UUID.nameUUIDFromBytes(request.getSource().getBytes(StandardCharsets.UTF_8)).toString()
                : UUID.randomUUID().toString();
        Document doc = Document.builder()
                .id(docId)
                .content(request.getContent().trim())
                .source(request.getSource() != null ? request.getSource() : docId + ".md")
                .type(request.getType() != null ? request.getType() : "general")
                .version(request.getVersion())
                .effectiveAt(request.getEffectiveAt())
                .scope(request.getScope() != null ? request.getScope() : "public")
                .build();
        ragService.indexDocuments(List.of(doc), request.getChunkStrategy());
        result.put("indexedChunks", 1);
        result.put("docId", docId);
        result.put("version", doc.getVersion());
        result.put("contentHash", doc.getContentHash());
        result.put("effectiveAt", doc.getEffectiveAt());
        result.put("scope", doc.getScope());
        log.info("知识库导入完成: docId={}, source={}, type={}, chunkStrategy={}",
                docId, doc.getSource(), doc.getType(), request.getChunkStrategy());
        return result;
    }

    @GetMapping("/cache/stats")
    @Operation(summary = "LLM 语义缓存统计", description = "导出精确/语义命中与未命中计数、命中率，供压测与监控使用")
    public Map<String, Object> cacheStats() {
        return semanticAnswerCacheService.stats();
    }

    @GetMapping("/status")
    @Operation(summary = "RAG 运行状态", description = "返回本地模型与混合检索链路信息，供演示页面展示")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("online", true);
        result.put("ollamaOnline", isOllamaOnline());
        result.put("chatModel", chatModel);
        result.put("embeddingModel", embeddingModel);
        result.put("vectorStore", "Milvus");
        result.put("vectorIndex", "ANN");
        result.put("retrieval", "Adaptive semantic/exact/hybrid + ANN/BM25 + RRF + configurable local neural rerank");
        result.put("pipeline", List.of("Query-time rewrite/decompose", "Adaptive ANN/BM25 recall", "RRF fusion", "Qwen3 local rerank with feature fallback", "Ollama generation"));
        result.put("reranker", Map.of("enabled", neuralRerankerEnabled,
                "model", "Qwen3-Reranker-0.6B", "localOnly", true));
        result.put("cache", semanticAnswerCacheService.stats());
        Map<String, Object> knowledgeBase = new HashMap<>();
        knowledgeBase.put("documents", ragService.countIndexedSources());
        knowledgeBase.put("chunks", ragService.countIndexedChunks());
        result.put("knowledgeBase", knowledgeBase);
        return result;
    }

    @GetMapping("/source")
    @Operation(summary = "读取来源全文", description = "按来源标识读取知识库入库时保存的原始文档全文")
    public Map<String, Object> source(@RequestParam String source,
                                     @RequestParam(required=false) String version) {
        Map<String, String> document = ragService.findSourceRevision(source,version);
        Map<String, Object> result = new HashMap<>();
        result.put("found", !document.isEmpty());
        result.put("source", document.getOrDefault("source", source));
        result.put("type", document.getOrDefault("type", "general"));
        result.put("content", document.getOrDefault("content", ""));
        result.put("version", document.getOrDefault("version", ""));
        result.put("contentHash", document.getOrDefault("contentHash", ""));
        result.put("version", document.getOrDefault("version", ""));
        result.put("contentHash", document.getOrDefault("contentHash", ""));
        result.put("effectiveAt", document.getOrDefault("effectiveAt", ""));
        result.put("scope", document.getOrDefault("scope", "public"));
        return result;
    }

    @GetMapping("/documents")
    @Operation(summary = "知识库文档清单", description = "列出已入库文档（展示名/类型/来源），供会员端帮助中心展示与来源溯源")
    public Map<String, Object> documents() {
        List<Map<String, String>> documents = ragService.listIndexedSources();
        Map<String, Object> result = new HashMap<>();
        result.put("total", documents.size());
        result.put("documents", documents);
        return result;
    }

    private boolean isOllamaOnline() {
        try {
            URI chatUri = URI.create(llmBaseUrl);
            URI tagsUri = new URI(chatUri.getScheme(), chatUri.getAuthority(), "/api/tags", null, null);
            HttpRequest request = HttpRequest.newBuilder(tagsUri)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            return HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }
}
