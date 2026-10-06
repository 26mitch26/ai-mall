package com.ai.mall.agent.ops.controller;

import com.ai.mall.agent.ops.model.ExecutionRecord;
import com.ai.mall.agent.ops.model.GateRecord;
import com.ai.mall.agent.ops.service.agent.ChangeAgent;
import com.ai.mall.agent.ops.service.agent.HealAgent;
import com.ai.mall.agent.ops.service.agent.Orchestrator;
import com.ai.mall.agent.ops.service.audit.GateDecisionStore;
import com.ai.mall.agent.ops.service.execution.SimulatedExecutor;
import com.ai.mall.agent.ops.service.metric.MetricsIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维控制面：指标上报、主动巡检、门控审批、模拟执行记录。
 *
 * <p>与 {@link AiOpsController} 的分工：后者是"演示入口"（注入异常看链路），
 * 这里是"运维入口"（喂真实指标、审批变更、查看推演结果）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
@Tag(name = "智能运维控制面", description = "指标采集、变更审批与模拟执行")
public class OpsControlController {

    private final MetricsIngestService ingestService;
    private final GateDecisionStore gateStore;
    private final ChangeAgent changeAgent;
    private final SimulatedExecutor executor;
    private final HealAgent healAgent;
    private final Orchestrator orchestrator;

    // ==================== 指标采集面 ====================

    @PostMapping("/metrics")
    @Operation(summary = "上报指标点",
            description = "采集器/被监控服务推送指标；超出基线时立即进入检测链路并可能触发处置流程")
    public ResponseEntity<?> reportMetric(@RequestBody Map<String, Object> request) {
        String metricName = (String) request.getOrDefault("metric_name", "cpu_usage_percent");
        Number value = (Number) request.getOrDefault("metric_value", 0);
        String service = (String) request.getOrDefault("target_service", "reported-service");
        Object incident = ingestService.ingest(metricName, value.doubleValue(), service);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("incidentTriggered", incident != null);
        body.put("incident", incident);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/metrics/watch-once")
    @Operation(summary = "立即巡检一次",
            description = "按配置的目标列表拉取一次 Micrometer 指标（只读 GET，targets 仅来自配置文件）")
    public Map<String, Object> watchOnce() {
        return ingestService.watchOnce();
    }

    @GetMapping("/metrics/watch-targets")
    @Operation(summary = "巡检目标列表", description = "返回配置中的被监控目标")
    public List<Map<String, Object>> watchTargets() {
        return ingestService.watchTargets();
    }

    @GetMapping("/metrics/series")
    @Operation(summary = "指标曲线", description = "返回指定指标最近上报的点")
    public List<Map<String, Object>> series(@RequestParam(defaultValue = "cpu_usage_percent") String metric,
                                            @RequestParam(required = false) String service) {
        return ingestService.recentSeries(metric, service);
    }

    // ==================== 门控审批闭环 ====================

    @GetMapping("/gate-decisions")
    @Operation(summary = "审批单列表", description = "status=pending_approval 时只返回待审批；缺省返回全部")
    public List<GateRecord> gateDecisions(@RequestParam(required = false) String status) {
        if ("pending_approval".equals(status)) {
            return gateStore.pending();
        }
        return gateStore.findAll();
    }

    @GetMapping("/gate-decisions/summary")
    @Operation(summary = "审批统计", description = "待审批数量与各状态计数")
    public Map<String, Object> gateSummary() {
        return gateStore.summary();
    }

    @PostMapping("/gate-decisions/{id}/approve")
    @Operation(summary = "批准变更", description = "批准后触发模拟执行，并把结果与历史成功率回写")
    public ResponseEntity<?> approve(@PathVariable String id,
                                     @RequestParam(defaultValue = "oncall") String approver) {
        GateRecord record = gateStore.find(id).orElse(null);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"pending_approval".equals(record.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "当前状态不可批准", "status", record.getStatus()));
        }
        ExecutionRecord execution = executor.execute(record);
        String summary = execution.getMode() + "/" + (execution.isSuccess() ? "推演通过" : "推演失败")
                + "，步骤 " + execution.getSteps().size() + " 步，耗时 " + execution.getTotalCostMs() + "ms";
        GateRecord updated = changeAgent.approve(id, approver, summary);
        // 真正回写历史成功率：此前 updateSuccessRate 无调用方，风险评分里的成功率因子是装饰
        changeAgent.updateSuccessRate(updated.getPlaybook(), execution.isSuccess());
        return ResponseEntity.ok(Map.of("gate", updated, "execution", execution));
    }

    @PostMapping("/gate-decisions/{id}/reject")
    @Operation(summary = "驳回变更", description = "驳回后不做任何处置，故障停在待审批状态")
    public ResponseEntity<?> reject(@PathVariable String id,
                                    @RequestParam(defaultValue = "oncall") String approver,
                                    @RequestParam(defaultValue = "人工驳回") String reason) {
        GateRecord record = gateStore.find(id).orElse(null);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"pending_approval".equals(record.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "当前状态不可驳回", "status", record.getStatus()));
        }
        return ResponseEntity.ok(changeAgent.reject(id, approver, reason));
    }

    // ==================== Playbook 与执行记录 ====================

    @GetMapping("/playbooks")
    @Operation(summary = "Playbook 目录", description = "内置处置剧本及其步骤与验证条件")
    public Map<String, Object> playbooks() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionMode", executor.mode());
        body.put("note", "本环境没有真实执行器，批准后执行的是模拟推演（simulated），不会触碰任何真实设施");
        body.put("items", healAgent.playbookNames().stream()
                .map(name -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("steps", healAgent.stepsOf(name));
                    item.put("verification", healAgent.verificationOf(name));
                    return item;
                })
                .toList());
        return body;
    }

    @GetMapping("/executions")
    @Operation(summary = "模拟执行记录", description = "返回最近的推演记录（内存）")
    public List<ExecutionRecord> executions() {
        return executor.recentExecutions();
    }

    @GetMapping("/healthz")
    @Operation(summary = "链路自检", description = "返回各环节可用性，便于确认事件总线与审批单仓库是否就绪")
    public Map<String, Object> healthz() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "agent-ops");
        body.put("executionMode", executor.mode());
        body.put("watchTargets", ingestService.watchTargets().size());
        body.put("gates", gateStore.summary());
        body.put("incidents", orchestrator.getAllIncidents().size());
        return body;
    }
}
