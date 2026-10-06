package com.ai.mall.agent.ops.controller;

import com.ai.mall.agent.ops.model.IncidentState;
import com.ai.mall.agent.ops.service.agent.Orchestrator;
import com.ai.mall.agent.ops.service.audit.GateDecisionStore;
import com.ai.mall.agent.ops.service.execution.SimulatedExecutor;
import com.ai.mall.agent.ops.service.metric.MetricsIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
@Tag(name = "智能运维", description = "智能运维接口")
public class AiOpsController {

    /** 演示模式下伪造的基线点数 */
    private static final int DEMO_BASELINE_POINTS = 12;

    private final Orchestrator orchestrator;
    private final MetricsIngestService ingestService;
    private final GateDecisionStore gateStore;
    private final SimulatedExecutor executor;

    @Value("${aiops.llm.model:qwen3.5-noVL:latest}")
    private String llmModel;

    @GetMapping("/capabilities")
    @Operation(summary = "运维 Agent 能力", description = "返回链路构成、检测算法与当前运行模式")
    public Map<String, Object> capabilities() {
        return Map.of(
                "online", true,
                "mode", "local-eventbus",
                "llmModel", llmModel,
                "pipeline", new String[]{"Monitor", "Bayesian + Neo4j RCA", "Playbook", "Change Gate"},
                "algorithms", new String[]{"3-Sigma", "EWMA", "Bayesian Inference", "Graph Traversal"},
                "detection", Map.of(
                        "algorithm", "3-Sigma AND EWMA (双算法投票)",
                        "window", 60,
                        "minSamples", 10,
                        "threshold", 3.0,
                        "note", "AND 投票以召回换精度：误报显著下降，召回同步下降"),
                "ingest", Map.of(
                        "reportEndpoint", "POST /api/v1/incidents/metrics",
                        "watchTargets", ingestService.watchTargets().size(),
                        "note", "watch 目标仅来自配置文件 aiops.watch.targets，不接受请求传入的 URL"),
                "execution", Map.of(
                        "mode", executor.mode(),
                        "note", "本环境无真实执行器，批准后执行的是模拟推演，不会触碰真实设施"),
                "gates", gateStore.summary()
        );
    }

    @PostMapping("/trigger")
    @Operation(summary = "触发故障处理", description = "手工注入一个指标点（演示入口；真实指标请走 POST /api/v1/incidents/metrics）")
    public IncidentState triggerIncident(@RequestBody Map<String, Object> request) {
        String metricName = (String) request.getOrDefault("metric_name", "cpu_usage_percent");
        double metricValue = ((Number) request.getOrDefault("metric_value", 95.3)).doubleValue();
        String service = (String) request.getOrDefault("target_service", "order-service");
        // 演示基线注入改为显式开关：默认关闭，避免"默认路径就在伪造数据"被追问时难以解释
        boolean demoMode = Boolean.TRUE.equals(request.getOrDefault("demo_mode", Boolean.FALSE));

        log.info("Triggering incident: {} = {} on {} (demoMode={})", metricName, metricValue, service, demoMode);
        if (demoMode) {
            log.warn("demo_mode=true：先注入 {} 个伪造基线点再注入尖峰，仅用于课堂演示", DEMO_BASELINE_POINTS);
            for (int i = 0; i < DEMO_BASELINE_POINTS; i++) {
                orchestrator.triggerIncident(metricName, 30.0 + (i % 3) * 0.4, service);
            }
        }
        return orchestrator.triggerIncident(metricName, metricValue, service);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询故障", description = "查询指定故障的处理状态")
    public IncidentState getIncident(@PathVariable String id) {
        return orchestrator.getIncident(id);
    }

    @GetMapping
    @Operation(summary = "查询所有故障", description = "查询所有故障处理记录")
    public Map<String, IncidentState> getAllIncidents() {
        return orchestrator.getAllIncidents();
    }
}
