package com.ai.mall.agent.customer.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 LLM 调用客户端（压测性能修复）。
 * <p>
 * 背景：客服链路此前走 Spring AI 的 OpenAI 兼容端点（/v1/chat/completions），
 * 而本机 Ollama 的 qwen3.5-4b 是带思维链（thinking）的模型，该端点无法关闭思考，
 * 每轮对话模型会先输出数千 token 的推理过程（实测约 6500 token / 114 秒），
 * 导致压测要么排队超时、要么被网关护栏兜底成拒答话术。
 * <p>
 * 本客户端直接调用 Ollama 原生 /api/chat，携带顶层 {@code think=false}
 * 关闭思维链（实测 100 token 从 31s @1727tokens 降到 2.3s @100 tokens），
 * 并设置 keep_alive 保持模型常驻（避免压测期间反复冷加载）与连接/读超时。
 * 该接口与 OpenAI 兼容端点同在本机，免外网、免 API Key。
 */
@Slf4j
@Service
public class AgentLlmClient {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    @org.springframework.beans.factory.annotation.Value("${ai.model.llm.context-tokens:4096}")
    private int contextTokens = 4096;
    /** Independent input-size guard; character counts are not exact token counts. */
    @Value("${ai.agent.context.max-characters:12000}")
    private int maxPromptCharacters = 12000;

    @org.springframework.beans.factory.annotation.Value("${ai.model.llm.threads:4}")
    private int threads = 4;

    @org.springframework.beans.factory.annotation.Value("${ai.model.llm.keep-alive:30m}")
    private String keepAlive = "30m";

    @org.springframework.beans.factory.annotation.Value("${ai.model.llm.gpu-layers:-1}")
    private int gpuLayers = -1;

    @org.springframework.beans.factory.annotation.Value("${ai.model.llm.release-embedding-before-generation:false}")
    private boolean releaseEmbeddingBeforeGeneration;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.embedding.options.model:bge-m3}")
    private String embeddingModelName = "bge-m3";

    public AgentLlmClient(ObjectMapper objectMapper,
                          @Value("${ai.model.llm.base-url:http://localhost:11434/api/chat}") String baseUrl,
                          @Value("${ai.model.llm.model:qwen3.5-4b}") String model,
                          @Value("${ai.model.llm.max-tokens:300}") int maxTokens,
                          @Value("${ai.model.llm.temperature:0.5}") double temperature) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
        log.info("AgentLlmClient 初始化: baseUrl={}, model={}, maxTokens={}, temperature={}",
                baseUrl, model, maxTokens, temperature);
    }

    /**
     * 单轮对话生成。返回模型正文（已关闭思维链）。
     *
     * @param prompt 完整指令（含知识库上下文）
     * @return 模型文本回答
     * @throws RuntimeException 模型/网络异常时抛出，交由上层熔断器降级
     */
    public String chat(String prompt) {
        if (prompt == null || prompt.length() > Math.max(1, maxPromptCharacters)) {
            throw new IllegalArgumentException("Prompt exceeds configured character budget");
        }
        if (!com.ai.mall.agent.customer.service.telemetry.AgentTelemetry.reserveModelCall()) {
            throw new IllegalStateException("Request model-call budget exhausted");
        }
        return com.ai.mall.agent.customer.service.telemetry.AgentTelemetry.observed("llm", () -> doChat(prompt));
    }

    private String doChat(String prompt) {
        long requestStart = System.currentTimeMillis();
        if (releaseEmbeddingBeforeGeneration) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                restTemplate.postForEntity(URI.create(baseUrl).resolve("/api/generate"),
                        new HttpEntity<>(objectMapper.writeValueAsString(Map.of("model", embeddingModelName, "keep_alive", 0)), headers), String.class);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot release embedding residency for bounded evaluation", failure);
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", prompt));
        body.put("messages", messages);
        body.put("stream", false);
        // 关闭 qwen3 思维链：OpenAI 兼容端点不支持该顶层参数，必须走 /api/chat
        body.put("think", false);
        // 保持模型常驻内存，压测期间避免冷加载（默认 keep_alive 5 分钟偏短）
        body.put("keep_alive", keepAlive);
        Map<String, Object> options = new HashMap<>();
        options.put("num_predict", maxTokens);
        options.put("temperature", temperature);
        options.put("num_ctx", Math.max(1024, contextTokens));
        options.put("num_thread", Math.max(1, threads));
        if (gpuLayers >= 0) options.put("num_gpu", gpuLayers);
        body.put("options", options);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(baseUrl), HttpMethod.POST, entity, String.class);
            long costMs = System.currentTimeMillis() - start;

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("LLM 调用失败 model={}, status={}, body={}",
                        model, response.getStatusCode(), response.getBody());
                throw new IllegalStateException("LLM 返回异常状态: " + response.getStatusCode());
            }

            JsonNode node = objectMapper.readTree(response.getBody());
            String content = node.path("message").path("content").asText("");
            int evalCount = node.path("eval_count").asInt(-1);
            com.ai.mall.agent.customer.service.telemetry.AgentTelemetry.recordLlm(costMs,
                    node.path("prompt_eval_count").asLong(-1), evalCount, "success");
            log.info("LLM 调用成功 model={}, 耗时{}ms, eval_count={}, contentLength={}",
                    model, costMs, evalCount, content.length());
            return content;
        } catch (Exception e) {
            com.ai.mall.agent.customer.service.telemetry.AgentTelemetry.recordLlm(System.currentTimeMillis() - requestStart, -1, -1, "failure");
            log.error("LLM 调用异常 model={}, err={}", model, e.getMessage());
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }
}
