package com.ai.mall.agent.ops.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 变更门控的审批单。
 *
 * <p>此前 ChangeAgent 只把决策写进内存 List 且没有任何查询接口，
 * 导致"需值班工程师审批 / 需团队负责人审批"这两档在系统里是死路：
 * 前端显示"待人工审批"，但没有任何接口能完成审批。现在审批单是一等公民：
 * 可查询、可落盘、可批准/驳回，批准后触发模拟执行并回写历史成功率。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateRecord {

    /** 审批单 ID（同时是 ChangeDecision 的 id） */
    private String id;

    /** 关联的告警 ID，即 incidentId */
    private String alertId;

    private String healActionId;

    /** 关联的处置动作（playbook 名） */
    private String playbook;

    private double riskScore;

    /** 需要的审批角色：auto / oncall-engineer / team-lead / cto */
    private String approver;

    /**
     * 审批状态：pending_approval / approved / rejected / executed。
     * auto 通过的门控直接以 approved 落库。
     */
    private String status;

    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;

    /** 审批人：接口调用方标识，未接入用户体系时由请求参数给出 */
    private String decidedBy;

    /** 模拟执行结果摘要（批准后回填） */
    private String executionSummary;
}
