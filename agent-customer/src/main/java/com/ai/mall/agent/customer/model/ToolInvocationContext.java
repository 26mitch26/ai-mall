package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用上下文
 * <p>
 * 携带本次调用的主体身份，用于工具鉴权与数据隔离：
 * 敏感工具（订单查询、创建售后工单等）必须绑定登录用户，
 * 未携带身份时按 fail-closed 原则直接拒绝执行，而不是"裸奔"调用后端。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocationContext {

    /** 会话ID */
    private String sessionId;

    /** 当前登录用户ID（memberId），游客场景为空 */
    private String memberId;

    /**
     * 用户 JWT，用于透传给后端服务做真实身份校验。
     * 仅存在于内存中，禁止写入日志与审计流水。
     */
    private String userToken;

    /** Server-created, frozen operation identity. Never copied from a model-generated context. */
    private String operationId;
    private String operationHash;
    private boolean writeApproved;

    /**
     * 构造匿名上下文（未登录用户）
     */
    public static ToolInvocationContext anonymous(String sessionId) {
        return ToolInvocationContext.builder().sessionId(sessionId).build();
    }

    public boolean isAuthenticated() {
        return memberId != null && !memberId.isBlank();
    }
}
