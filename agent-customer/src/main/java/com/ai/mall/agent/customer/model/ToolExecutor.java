package com.ai.mall.agent.customer.model;

/**
 * 工具执行器
 * <p>
 * 相比早期版本 {@code execute(String)}，这里显式传入 {@link ToolInvocationContext}，
 * 让工具在执行时能够拿到调用主体身份，从而做鉴权与数据隔离，
 * 避免"只凭订单号就能查到任意用户数据"的越权问题。
 */
@FunctionalInterface
public interface ToolExecutor {
    /**
     * 执行工具
     *
     * @param parameters 工具参数（JSON 字符串）
     * @param context    调用上下文（含用户身份），公开工具场景下可能为 null
     * @return 工具返回的原始内容（由 ToolRegistry 统一净化后再交给模型）
     */
    String execute(String parameters, ToolInvocationContext context);
}
