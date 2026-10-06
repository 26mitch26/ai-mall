package com.ai.mall.agent.ops.service.metric;

import com.ai.mall.agent.ops.service.agent.Orchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 指标采集面：让这套运维 Agent 从"人工点按钮"变成"能被真实指标驱动"。
 *
 * <p>三条输入路径：
 * <ol>
 *   <li><b>上报端点</b> {@code POST /api/v1/incidents/metrics}：被监控服务或采集器把指标点推上来；</li>
 *   <li><b>主动拉取</b>：{@code @Scheduled} 按 {@code aiops.watch.targets} 定时 GET
 *       {@code {baseUrl}/actuator/metrics/{metric}}（Spring Boot 自带 Micrometer 端点），
 *       取到后送入检测 —— 这是"主动感知"的最小可信实现；</li>
 *   <li><b>手工触发</b>：保留原 {@code POST /trigger} 供课堂演示。</li>
 * </ol>
 *
 * <p><b>安全说明</b>：主动拉取的 targets 只允许来自配置文件（{@code aiops.watch.targets}），
 * <b>不接受任何 HTTP 请求传入的 URL</b>，否则就变成一个可被利用的 SSRF 入口。
 * 采集动作是只读 GET，且仅在配置了 targets 时才执行。
 */
@Slf4j
@Service
public class MetricsIngestService {

    /** 每个指标保留的最近点数，用于前端曲线展示。 */
    private static final int SERIES_CAPACITY = 60;

    private final Orchestrator orchestrator;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RestTemplate http;
    private final int timeoutMs;
    private final List<WatchTarget> watchTargets;

    private final Map<String, Deque<Double>> series = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

    public MetricsIngestService(Orchestrator orchestrator, ObjectMapper objectMapper, MeterRegistry meterRegistry,
                                @Value("${aiops.watch.timeout-ms:2000}") int timeoutMs,
                                @Value("${aiops.watch.targets:}") List<String> targets) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.timeoutMs = timeoutMs;
        this.watchTargets = parseTargets(targets);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.http = new RestTemplate(factory);
    }

    /**
     * 上报单个指标点：记录曲线 → 刷新 Micrometer 指标 → 送入异常检测链路。
     *
     * @return 非空表示该点触发了异常（返回故障状态）；null 表示已记录但未触发
     */
    public Object ingest(String metricName, double value, String service) {
        record(metricName, value);
        return orchestrator.triggerIncident(metricName, value, service);
    }

    /**
     * 定时拉取被监控目标的 Micrometer 指标并送入检测。
     *
     * <p>targets 为空时（默认）直接返回，不产生任何外部请求。
     */
    @Scheduled(fixedDelayString = "${aiops.watch.interval-ms:30000}", initialDelayString = "${aiops.watch.initial-delay-ms:20000}")
    public void watchAndIngest() {
        if (watchTargets.isEmpty()) {
            return;
        }
        for (WatchTarget target : watchTargets) {
            try {
                Double value = fetch(target);
                if (value != null) {
                    ingest(target.metric(), value, target.service());
                    log.debug("Watched {} = {} for {}", target.metric(), value, target.service());
                }
            } catch (Exception e) {
                log.warn("Watch target {} failed: {}", target, e.getMessage());
            }
        }
    }

    /** 主动拉取一次（供接口触发，便于演示"不依赖定时器也能采到"）。 */
    public Map<String, Object> watchOnce() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> ticks = new ArrayList<>();
        for (WatchTarget target : watchTargets) {
            Map<String, Object> tick = new LinkedHashMap<>();
            tick.put("service", target.service());
            tick.put("metric", target.metric());
            try {
                Double value = fetch(target);
                tick.put("value", value);
                tick.put("status", value == null ? "no_measurement" : "ok");
                if (value != null) {
                    tick.put("incident", ingest(target.metric(), value, target.service()) != null);
                }
            } catch (Exception e) {
                tick.put("status", "error");
                tick.put("error", e.getClass().getSimpleName());
            }
            ticks.add(tick);
        }
        summary.put("targets", watchTargets.size());
        summary.put("ticks", ticks);
        return summary;
    }

    /** 最近若干个点的曲线数据（供前端展示）。 */
    public List<Map<String, Object>> recentSeries(String metricName, String service) {
        String key = seriesKey(metricName, service);
        Deque<Double> points = series.get(key);
        if (points == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (points) {
            for (Double point : points) {
                out.add(Map.of("value", point));
            }
        }
        return out;
    }

    public List<Map<String, Object>> watchTargets() {
        return watchTargets.stream().map(WatchTarget::toMap).toList();
    }

    private Double fetch(WatchTarget target) throws com.fasterxml.jackson.core.JsonProcessingException {
        String url = target.baseUrl() + "/actuator/metrics/" + target.metric();
        ResponseEntity<String> response = http.getForEntity(url, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return null;
        }
        JsonNode measurements = objectMapper.readTree(response.getBody()).path("measurements");
        for (JsonNode measurement : measurements) {
            if ("VALUE".equals(measurement.path("statistic").asText())) {
                return measurement.path("value").asDouble();
            }
        }
        return null;
    }

    private void record(String metricName, double value) {
        Deque<Double> points = series.computeIfAbsent(metricName, key -> new ArrayDeque<>());
        synchronized (points) {
            if (points.size() >= SERIES_CAPACITY) {
                points.removeFirst();
            }
            points.addLast(value);
        }
        // 同步到 Micrometer，让 /actuator/prometheus 能抓到被监控指标
        AtomicReference<Double> holder = gauges.computeIfAbsent(metricName, name -> {
            AtomicReference<Double> ref = new AtomicReference<>(value);
            Gauge.builder("aiops.metric." + name, ref, AtomicReference::get)
                    .description("Ingested metric " + name)
                    .register(meterRegistry);
            return ref;
        });
        holder.set(value);
    }

    private String seriesKey(String metricName, String service) {
        return service == null ? metricName : metricName + "@" + service;
    }

    /**
     * 解析 watch 目标，格式 {@code baseUrl|metric|service}。
     * 只接受配置来源，不接受请求参数，避免 SSRF。
     */
    private List<WatchTarget> parseTargets(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<WatchTarget> parsed = new ArrayList<>();
        for (String item : raw) {
            String[] parts = item.split("\\|");
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                log.warn("Ignoring malformed watch target: {}", item);
                continue;
            }
            String service = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : "watched-service";
            String baseUrl = parts[0].trim();
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            parsed.add(new WatchTarget(baseUrl, parts[1].trim(), service));
        }
        log.info("Watching {} metric target(s): {}", parsed.size(), parsed);
        return List.copyOf(parsed);
    }

    /** 被监控目标。 */
    public record WatchTarget(String baseUrl, String metric, String service) {
        public Map<String, Object> toMap() {
            return Map.of("baseUrl", baseUrl, "metric", metric, "service", service);
        }
    }
}
