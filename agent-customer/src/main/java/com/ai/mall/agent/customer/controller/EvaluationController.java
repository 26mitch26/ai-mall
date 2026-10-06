package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.service.rag.RagService;
import com.ai.mall.agent.customer.service.security.InputSanitizer;
import com.ai.mall.agent.customer.service.telemetry.AgentTelemetry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Read-only evaluator calls the same production retrieval pipeline, not a parallel in-memory implementation. */
@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {
    private final RagService rag;
    private final InputSanitizer sanitizer;
    public record Request(String query, Integer topK, Integer modelBudget, String context) {}
    public EvaluationController(RagService rag,InputSanitizer sanitizer) { this.rag=rag;this.sanitizer=sanitizer; }
    @GetMapping("/configuration")
    public Map<String,Object> configuration(
            @org.springframework.beans.factory.annotation.Value("${ai.model.llm.model:qwen3.5-4b}") String model,
            @org.springframework.beans.factory.annotation.Value("${ai.model.llm.context-tokens:4096}") int contextTokens,
            @org.springframework.beans.factory.annotation.Value("${ai.model.llm.keep-alive:30m}") String keepAlive,
            @org.springframework.beans.factory.annotation.Value("${ai.model.llm.gpu-layers:-1}") int gpuLayers,
            @org.springframework.beans.factory.annotation.Value("${ai.model.llm.release-embedding-before-generation:false}") boolean releaseEmbedding,
            @org.springframework.beans.factory.annotation.Value("${ai.customer.semantic-cache.enabled:true}") boolean cache,
            @org.springframework.beans.factory.annotation.Value("${ai.memory.archive-enabled:true}") boolean archive,
            @org.springframework.beans.factory.annotation.Value("${ai.rag.retrieval.strategy:auto}") String strategy,
            @org.springframework.beans.factory.annotation.Value("${ai.rag.reranker.semantic-feature-enabled:true}") boolean semanticFeature,
            @org.springframework.beans.factory.annotation.Value("${ai.customer.explicit-handoff.enabled:true}") boolean handoff,
            @org.springframework.beans.factory.annotation.Value("${ai.customer.policy-direct.enabled:true}") boolean policy,
            @org.springframework.beans.factory.annotation.Value("${ai.model.llm.base-url:http://localhost:11434/api/chat}") String llmUrl,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.base-url:http://localhost:11434}") String embeddingUrl) {
        return Map.ofEntries(Map.entry("model", model), Map.entry("contextTokens", contextTokens),
                Map.entry("keepAlive", keepAlive), Map.entry("gpuLayers", gpuLayers), Map.entry("releaseEmbeddingBeforeGeneration", releaseEmbedding), Map.entry("semanticCacheEnabled", cache),
                Map.entry("archiveEnabled", archive), Map.entry("retrievalStrategy", strategy),
                Map.entry("semanticRerankFeatureEnabled", semanticFeature), Map.entry("explicitHandoffEnabled", handoff),
                Map.entry("policyDirectEnabled", policy), Map.entry("localBackends", localUrl(llmUrl) && localUrl(embeddingUrl)));
    }
    private boolean localUrl(String url) {
        try { return java.util.Set.of("localhost", "127.0.0.1", "::1").contains(java.net.URI.create(url).getHost()); }
        catch (RuntimeException ignored) { return false; }
    }
    @PostMapping("/retrieve")
    public Map<String,Object> retrieve(@RequestBody Request request) {
        if(request.query()==null || request.query().isBlank() || request.query().length()>500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Query must contain 1-500 characters");
        String query=sanitizer.sanitize(request.query());
        if(InputSanitizer.BLOCKED_MESSAGE.equals(query)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsafe query");
        if(request.context()!=null && !request.context().isBlank()) {
            if(request.context().length()>300) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Context exceeds limit");
            String previous=sanitizer.sanitize(request.context());
            if(InputSanitizer.BLOCKED_MESSAGE.equals(previous)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsafe context");
            var turn=new com.ai.mall.agent.customer.model.ChatMessage();turn.setRole("user");turn.setContent(previous);
            query=com.ai.mall.agent.customer.service.agent.StandaloneQueryResolver.resolve(query,java.util.List.of(turn));
        }
        int topK=request.topK()==null?5:request.topK();
        if(topK<1 || topK>10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"topK must be 1-10");
        int budget=request.modelBudget()==null?0:request.modelBudget();
        if(budget<0 || budget>1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Retrieval model budget must be 0 or 1");
        try (var trace=AgentTelemetry.open(budget)) {
            RagService.RetrievalOutcome outcome=rag.retrieveWithEvidence(query,topK);
            return Map.of("retrieval",outcome,"trace",trace.summary());
        }
    }
}
