package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 审计事件
 * <p>
 * 记录一次会话中的关键节点，为人工校对、幻觉率统计、越权追溯提供依据。
 * 事件内容一律经过脱敏后才允许落库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    /** 事件ID */
    private String id;

    /** 会话ID */
    private String sessionId;

    /** 用户ID（可为空） */
    private String memberId;

    /** 事件类型，取值见 {@link AuditEventType} */
    private String type;

    /** 事件摘要（已脱敏） */
    private String detail;

    /** 命中的风险类型（如护栏拦截原因），无风险时为空 */
    private String riskType;

    /** 是否被拦截 */
    private boolean blocked;

    /** 耗时（毫秒） */
    private long costMs;

    /** 事件发生时间 */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
