package com.ai.mall.agent.ops.controller;

import com.ai.mall.agent.ops.model.IncidentState;
import com.ai.mall.agent.ops.service.agent.Orchestrator;
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

    private final Orchestrator orchestrator;

    @Value("${aiops.llm.model:qwen3.5-noVL:latest}")
    private String llmModel;

    @GetMapping("/capabilities")
    @Operation(summary = "运维 Agent 能力", description = "返回课设演示链路与本地模型信息")
    public Map<String, Object> capabilities() {
        return Map.of(
                "online", true,
                "mode", "local-eventbus",
                "llmModel", llmModel,
                "pipeline", new String[]{"Monitor", "Bayesian + Neo4j RCA", "Playbook", "Change Gate"},
                "algorithms", new String[]{"3-Sigma", "EWMA", "Bayesian Inference", "Graph Traversal"}
        );
    }

    @PostMapping("/trigger")
    @Operation(summary = "触发故障处理", description = "触发一个故障处理流程")
    public IncidentState triggerIncident(@RequestBody Map<String, Object> request) {
        String metricName = (String) request.getOrDefault("metric_name", "cpu_usage_percent");
        double metricValue = ((Number) request.getOrDefault("metric_value", 95.3)).doubleValue();
        String service = (String) request.getOrDefault("target_service", "order-service");
        boolean demoMode = !Boolean.FALSE.equals(request.getOrDefault("demo_mode", true));

        log.info("Triggering incident: {} = {} on {}", metricName, metricValue, service);
        if (demoMode) {
            // 课设演示自动建立稳定基线，随后再注入尖峰；检测本身仍由 3-Sigma + EWMA 完成。
            for (int i = 0; i < 12; i++) {
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
