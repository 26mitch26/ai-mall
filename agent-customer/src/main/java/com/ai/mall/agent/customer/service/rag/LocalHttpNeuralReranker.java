package com.ai.mall.agent.customer.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** HTTP adapter for the optional local Qwen3-Reranker Python service. */
@Component
public class LocalHttpNeuralReranker implements NeuralReranker {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String endpoint;
    private final Duration timeout;

    public LocalHttpNeuralReranker(ObjectMapper mapper,
            @Value("${ai.rag.reranker.url:http://localhost:8092/rerank}") String endpoint,
            @Value("${ai.rag.reranker.timeout:PT2S}") Duration timeout) {
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<Double> score(String query, List<String> passages) throws Exception {
        String body = mapper.writeValueAsString(Map.of("query", query, "documents", passages));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Local reranker returned HTTP " + response.statusCode());
        }
        JsonNode scores = mapper.readTree(response.body()).path("scores");
        if (!scores.isArray() || scores.size() != passages.size()) {
            throw new IllegalStateException("Local reranker returned an invalid score count");
        }
        java.util.ArrayList<Double> result = new java.util.ArrayList<>(scores.size());
        for (JsonNode score : scores) {
            double value = score.asDouble(Double.NaN);
            if (!Double.isFinite(value)) throw new IllegalStateException("Invalid reranker score");
            result.add(value);
        }
        return result;
    }
}
