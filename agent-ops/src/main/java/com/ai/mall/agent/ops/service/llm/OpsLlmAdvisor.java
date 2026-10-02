package com.ai.mall.agent.ops.service.llm;

import com.ai.mall.agent.ops.model.AlertEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** 使用本机 Ollama 将算法结果整理为适合现场演示的中文根因摘要。 */
@Slf4j
@Service
public class OpsLlmAdvisor {

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String model;
    private final boolean enabled;

    public OpsLlmAdvisor(ObjectMapper objectMapper,
                         @Value("${aiops.llm.base-url:http://localhost:11434/api/chat}") String baseUrl,
                         @Value("${aiops.llm.model:qwen3.5-noVL:latest}") String model,
                         @Value("${aiops.llm.enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.model = model;
        this.enabled = enabled;
    }

    public String summarize(AlertEvent alert, String rootCause, double confidence,
                            List<String> impactChain, List<String> suggestedActions) {
        String fallback = String.format("%s 的 %s 指标达到 %.2f，算法定位根因为 %s（置信度 %.0f%%）。",
                alert.getTargetService(), alert.getMetricName(), alert.getMetricValue(),
                rootCause, confidence * 100);
        if (!enabled) {
            return fallback;
        }

        String prompt = "你是AIOps值班专家。请基于下面的确定性算法结果，输出不超过120字的中文根因摘要，"
                + "不得编造新指标。服务=" + alert.getTargetService()
                + "，指标=" + alert.getMetricName() + "，值=" + alert.getMetricValue()
                + "，根因=" + rootCause + "，置信度=" + confidence
                + "，影响链=" + impactChain + "，建议动作=" + suggestedActions;
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "stream", false,
                    "think", false,
                    "keep_alive", "30m",
                    "options", Map.of("num_predict", 160, "temperature", 0.2)
            );
            String response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            String content = node.path("message").path("content").asText("").trim();
            return content.isEmpty() ? fallback : content;
        } catch (Exception e) {
            log.warn("Ollama 运维摘要生成失败，使用算法摘要: {}", e.getMessage());
            return fallback;
        }
    }
}
