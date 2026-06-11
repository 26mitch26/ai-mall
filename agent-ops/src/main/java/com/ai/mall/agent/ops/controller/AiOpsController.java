package com.ai.mall.agent.ops.controller;

import com.ai.mall.agent.ops.model.IncidentState;
import com.ai.mall.agent.ops.service.agent.Orchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
@Tag(name = "智能运维", description = "智能运维接口")
public class AiOpsController {

    private final Orchestrator orchestrator;

    @PostMapping("/trigger")
    @Operation(summary = "触发故障处理", description = "触发一个故障处理流程")
    public IncidentState triggerIncident(@RequestBody Map<String, Object> request) {
        String metricName = (String) request.getOrDefault("metric_name", "cpu_usage_percent");
        double metricValue = ((Number) request.getOrDefault("metric_value", 95.3)).doubleValue();
        String service = (String) request.getOrDefault("target_service", "order-service");

        log.info("Triggering incident: {} = {} on {}", metricName, metricValue, service);
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
