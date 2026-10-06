package com.ai.mall.agent.ops.service.metric;

import com.ai.mall.agent.ops.service.agent.Orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指标采集面。
 *
 * <p>覆盖两条关键行为：上报的点会真的进入检测链路；未配置 watch 目标时
 * 定时任务不产生任何外部请求（默认零副作用）。
 */
class MetricsIngestServiceTest {

    private Orchestrator orchestrator;
    private MetricsIngestService service;

    @BeforeEach
    void setUp() {
        orchestrator = mock(Orchestrator.class);
        service = newService(List.of());
    }

    private MetricsIngestService newService(List<String> targets) {
        return new MetricsIngestService(orchestrator, new ObjectMapper(), new SimpleMeterRegistry(),
                500, targets);
    }

    @Test
    void ingestRecordsSeriesEvenWhenNoAnomaly() {
        when(orchestrator.triggerIncident(anyString(), anyDouble(), anyString())).thenReturn(null);

        assertNull(service.ingest("cpu_usage_percent", 42.0, "order-service"));

        var series = service.recentSeries("cpu_usage_percent", null);
        assertEquals(1, series.size());
        assertEquals(42.0, ((Number) series.get(0).get("value")).doubleValue(), 0.001);
    }

    @Test
    void ingestReturnsIncidentWhenAnomalyDetected() {
        com.ai.mall.agent.ops.model.IncidentState incident =
                com.ai.mall.agent.ops.model.IncidentState.builder().id("i1").status("detected").build();
        when(orchestrator.triggerIncident(anyString(), anyDouble(), anyString())).thenReturn(incident);

        assertNotNull(service.ingest("cpu_usage_percent", 99.0, "order-service"));
    }

    @Test
    void seriesIsCappedToKeepMemoryBounded() {
        when(orchestrator.triggerIncident(anyString(), anyDouble(), anyString())).thenReturn(null);
        for (int i = 0; i < 80; i++) {
            service.ingest("cpu_usage_percent", 30.0 + i, "order-service");
        }
        assertEquals(60, service.recentSeries("cpu_usage_percent", null).size());
    }

    @Test
    void emptyWatchTargetsProduceNoExternalCalls() {
        service.watchAndIngest();
        assertTrue(service.watchTargets().isEmpty());
        assertEquals(0, service.watchOnce().get("targets"));
    }

    @Test
    void malformedWatchTargetsAreIgnored() {
        // 只接受 baseUrl|metric[|service] 形式，脏配置必须被丢弃而不是拼出错误 URL
        MetricsIngestService dirty = newService(List.of("not-a-valid-target", "  |  |  "));
        assertTrue(dirty.watchTargets().isEmpty(),
                "格式非法的 watch 目标必须被忽略");
    }

    @Test
    void validWatchTargetIsParsedWithServiceName() {
        MetricsIngestService configured = newService(List.of("http://localhost:8087/|jvm.memory.used|portal"));
        var targets = configured.watchTargets();
        assertEquals(1, targets.size());
        assertEquals("http://localhost:8087", targets.get(0).get("baseUrl"));
        assertEquals("jvm.memory.used", targets.get(0).get("metric"));
        assertEquals("portal", targets.get(0).get("service"));
    }
}
