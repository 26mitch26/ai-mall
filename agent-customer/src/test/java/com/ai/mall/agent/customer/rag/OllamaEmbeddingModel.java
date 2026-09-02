package com.ai.mall.agent.customer.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于本机 Ollama 的真语义 Embedding 模型（RAG 离线/本地评测专用）
 * <p>
 * 用途：证明"召回率天花板"的真正解锁路径——把本地哈希 embedding 换成语义 embedding（如
 * nomic-embed-text / mxbai-embed-large / bge-m3）后，同义表达类 query 的区分度才提得上来。
 * <p>
 * 说明：
 * - 调用本机 Ollama 的 {@code POST /api/embed}，无需外部 API key、无需走外网。
 * - 通过系统属性 {@code rag.embed.model} 指定模型（默认 nomic-embed-text）。
 * - Ollama 未启动或模型缺失时，调用抛异常，由评测入口决定跳过（不阻塞离线链路）。
 */
public final class OllamaEmbeddingModel implements EmbeddingModel {

    private static final String BASE_URL =
            System.getProperty("rag.ollama.url", "http://localhost:11434");
    private static final String MODEL =
            System.getProperty("rag.embed.model", "nomic-embed-text");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public int dimensions() {
        // 实际维度由首次调用返回的向量长度决定；此处动态返回 JSON 长度不可行，故声明为 0（用不到）。
        return 0;
    }

    @Override
    public float[] embed(String text) {
        List<Float> v = embedBatch(List.of(text)).get(0);
        return toFloats(v);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<List<Float>> batches = embedBatch(texts);
        List<Embedding> results = new ArrayList<>(batches.size());
        for (int i = 0; i < batches.size(); i++) {
            results.add(new Embedding(toFloats(batches.get(i)), i));
        }
        return new EmbeddingResponse(results);
    }

    /** 批量编码。同一批能减少 HTTP 往返，评测 24 条 query 时可显著提速。 */
    private List<List<Float>> embedBatch(List<String> texts) {
        try {
            ArrayNode inputArr = mapper.createArrayNode();
            for (String t : texts) {
                inputArr.add(t == null ? "" : t);
            }
            String body = mapper.createObjectNode()
                    .put("model", MODEL)
                    .set("input", inputArr)
                    .toString();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/embed"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(2))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Ollama /api/embed 返回 " + resp.statusCode()
                        + ": " + resp.body());
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode emb = root.path("embeddings");
            if (!emb.isArray() || emb.isEmpty()) {
                throw new IllegalStateException("Ollama 返回缺少 embeddings 数组: " + resp.body());
            }
            List<List<Float>> out = new ArrayList<>();
            for (JsonNode vec : emb) {
                List<Float> floats = new ArrayList<>(vec.size());
                for (JsonNode f : vec) {
                    floats.add((float) f.asDouble());
                }
                out.add(floats);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Ollama 语义编码失败(本机需已 `ollama serve` 并安装模型 "
                    + MODEL + "): " + e.getMessage(), e);
        }
    }

    private float[] toFloats(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /** 供日志/提示使用：探测本机 Ollama 是否在线 */
    static boolean available() {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}