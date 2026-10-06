package com.ai.mall.agent.ops.service.execution;

import com.ai.mall.agent.ops.model.ExecutionRecord;
import com.ai.mall.agent.ops.model.GateRecord;
import com.ai.mall.agent.ops.service.agent.HealAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模拟执行器：把"批准后的处置"做成可观察、可复现的推演，而不是一句日志。
 *
 * <p><b>刻意不提供真实执行模式</b>。项目没有任何 K8s / 发布平台 / SSH / 云 API 客户端依赖，
 * 在本机 demo 里真的去重启服务既不可能也不该做。因此这里的语义严格限定为：
 * <ul>
 *   <li>逐条推演 playbook 的步骤，检查步骤是否自洽、顺序是否合理；</li>
 *   <li>对 playbook 声明的验证条件给出<b>可否判定</b>的结论（而不是伪装成"已验证通过"）；</li>
 *   <li>把推演结果回写审批单，使"批准 → 执行 → 成功率回写"这条链路真正闭合。</li>
 * </ul>
 *
 * <p>{@code aiops.execution.mode=disabled} 时完全跳过推演（用于纯审批演练）。
 * 未来若接入真实执行器，只需另写一个实现相同的接口，链路其余部分无需改动。
 */
@Slf4j
@Service
public class SimulatedExecutor {

    /** 固定基准耗时，让模拟结果可复现、可对比（不引入随机数）。 */
    private static final long BASE_STEP_COST_MS = 50L;
    private static final long STEP_COST_INCREMENT_MS = 37L;

    private final HealAgent healAgent;
    private final String mode;

    /** 最近的推演记录（内存）。真实执行器接入后应改为落库或接审计系统。 */
    private final java.util.Deque<ExecutionRecord> history = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int HISTORY_CAPACITY = 100;

    public SimulatedExecutor(HealAgent healAgent,
                             @Value("${aiops.execution.mode:simulated}") String mode) {
        this.healAgent = healAgent;
        this.mode = mode == null || mode.isBlank() ? "simulated" : mode.trim();
    }

    public boolean enabled() {
        return !"disabled".equalsIgnoreCase(mode);
    }

    /** 最近的推演记录，最新在前。 */
    public List<ExecutionRecord> recentExecutions() {
        return List.copyOf(history);
    }

    /**
     * 推演一次处置动作。
     *
     * @return 推演记录；{@code success} 表示步骤是否可执行且验证条件可判定
     */
    public ExecutionRecord execute(GateRecord gate) {
        if (!enabled()) {
            return remember(ExecutionRecord.builder()
                    .id(UUID.randomUUID().toString())
                    .gateId(gate.getId())
                    .alertId(gate.getAlertId())
                    .playbook(gate.getPlaybook())
                    .mode("disabled")
                    .success(false)
                    .steps(List.of())
                    .verification("执行被 aiops.execution.mode=disabled 关闭")
                    .totalCostMs(0)
                    .executedAt(LocalDateTime.now())
                    .build());
        }

        List<String> steps = healAgent.stepsOf(gate.getPlaybook());
        if (steps.isEmpty()) {
            // 没有匹配到 playbook 的步骤：这是需要人关注的信号，而不是"成功"
            return remember(ExecutionRecord.builder()
                    .id(UUID.randomUUID().toString())
                    .gateId(gate.getId())
                    .alertId(gate.getAlertId())
                    .playbook(gate.getPlaybook())
                    .mode("simulated")
                    .success(false)
                    .steps(List.of())
                    .verification("未匹配到该处置动作的步骤定义，无法推演")
                    .totalCostMs(0)
                    .executedAt(LocalDateTime.now())
                    .build());
        }

        List<ExecutionRecord.StepResult> stepResults = new ArrayList<>(steps.size());
        long total = 0L;
        for (int i = 0; i < steps.size(); i++) {
            long cost = BASE_STEP_COST_MS + (long) i * STEP_COST_INCREMENT_MS;
            total += cost;
            stepResults.add(ExecutionRecord.StepResult.builder()
                    .index(i + 1)
                    .step(steps.get(i))
                    .simulatedOutcome("SIMULATED_OK")
                    .costMs(cost)
                    .build());
        }
        String declaredVerification = healAgent.verificationOf(gate.getPlaybook());
        String verification = "SIMULATED_CHECK（未真实执行）：" + declaredVerification
                + " —— 判定口径可执行，但本环境无真实执行器，故仅推演";

        log.info("Simulated execution for playbook {} ({} steps), gate={}", gate.getPlaybook(), steps.size(), gate.getId());
        return remember(ExecutionRecord.builder()
                .id(UUID.randomUUID().toString())
                .gateId(gate.getId())
                .alertId(gate.getAlertId())
                .playbook(gate.getPlaybook())
                .mode("simulated")
                .success(true)
                .steps(stepResults)
                .verification(verification)
                .totalCostMs(total)
                .executedAt(LocalDateTime.now())
                .build());
    }

    private ExecutionRecord remember(ExecutionRecord record) {
        history.addFirst(record);
        while (history.size() > HISTORY_CAPACITY) {
            history.pollLast();
        }
        return record;
    }

    public String mode() {
        return mode;
    }
}
