package com.ai.mall.agent.ops.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模拟执行记录。
 *
 * <p><b>这是模拟执行，不是真实变更</b>：项目没有 K8s / 发布平台 / SSH 等任何执行器依赖，
 * 因此不可能也不应该在本机 demo 里真的去重启服务。记录里的每一步都是"如果执行会发生什么"的
 * 推演，用于验证 playbook 的步骤是否自洽、验证条件是否可判定。
 * 真实执行器接入时，只需替换 {@code SimulatedExecutor} 的实现，链路其余部分不变。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionRecord {

    private String id;

    private String gateId;

    private String alertId;

    private String playbook;

    /** simulated 表示模拟执行，disabled 表示执行被配置关闭 */
    private String mode;

    /** true 表示推演通过（步骤自洽且验证条件满足） */
    private boolean success;

    private List<StepResult> steps;

    /** playbook 里声明的验证条件及其判定结果 */
    private String verification;

    private long totalCostMs;

    private LocalDateTime executedAt;

    /** 单步推演结果 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepResult {
        private int index;
        private String step;
        private String simulatedOutcome;
        private long costMs;
    }
}
