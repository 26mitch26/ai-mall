package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审计事件类型
 */
@Getter
@AllArgsConstructor
public enum AuditEventType {

    /** 用户提问 */
    USER_QUERY("user_query"),
    /** 工具调用 */
    TOOL_CALL("tool_call"),
    /** 工具返回结果 */
    TOOL_RESULT("tool_result"),
    /** 护栏拦截 */
    GUARDRAIL_BLOCK("guardrail_block"),
    /** 最终回答 */
    FINAL_ANSWER("final_answer"),
    /** 用户输入被注入防护拦截 */
    INPUT_BLOCKED("input_blocked"),
    /** 用户反馈（人工校对闭环） */
    FEEDBACK("feedback");

    private final String code;
}
