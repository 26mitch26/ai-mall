package com.ai.mall.agent.customer.service.security;

import com.ai.mall.agent.customer.model.ToolInvocationContext;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具调用鉴权
 * <p>
 * 解决两个安全问题：
 * 1. 越权访问：模型可以点名调用任意工具，若不做鉴权，未登录用户也能按订单号查询他人订单、创建售后工单。
 * 2. 未登记工具：模型可能"幻觉"出并不存在的工具名，必须拒绝而不是带着空上下文继续执行。
 * <p>
 * 策略（fail-closed）：无法确定身份时一律拒绝，而不是放行。
 */
@Slf4j
@Component
public class ToolAccessGuard {

    /**
     * 工具敏感度分级
     */
    public enum Sensitivity {
        /** 公开数据，无需登录（如商品搜索） */
        PUBLIC,
        /** 用户私有数据，必须登录（如订单查询） */
        USER_DATA,
        /** 用户写操作，必须登录且携带用户令牌（如创建售后工单） */
        USER_WRITE
    }

    /**
     * 工具敏感度登记表。未登记的工具一律拒绝调用。
     */
    private static final Map<String, Sensitivity> TOOL_SENSITIVITY = Map.of(
            "search_products", Sensitivity.PUBLIC,
            "get_order_info", Sensitivity.USER_DATA,
            "list_my_orders", Sensitivity.USER_DATA,
            "create_after_sale", Sensitivity.USER_WRITE,
            "place_order", Sensitivity.USER_WRITE,
            "cancel_order", Sensitivity.USER_WRITE
    );

    @Getter
    @AllArgsConstructor
    public static class Decision {
        private final boolean allowed;
        private final String reason;

        public static Decision allow() {
            return new Decision(true, "ok");
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * 判定一次工具调用是否被允许
     *
     * @param toolName 工具名
     * @param context  调用上下文（含用户身份）
     * @return 鉴权结果
     */
    public Decision authorize(String toolName, ToolInvocationContext context) {
        if (toolName == null || toolName.isBlank()) {
            return Decision.deny("工具名为空");
        }

        Sensitivity sensitivity = TOOL_SENSITIVITY.get(toolName);
        if (sensitivity == null) {
            log.warn("工具未登记，拒绝调用: {}", toolName);
            return Decision.deny("未登记的工具: " + toolName);
        }

        if (sensitivity == Sensitivity.PUBLIC) {
            return Decision.allow();
        }

        // 以下等级均要求已登录
        if (context == null || !context.isAuthenticated()) {
            log.warn("敏感工具[{}]缺少用户身份，按 fail-closed 拒绝", toolName);
            return Decision.deny("该操作需要登录后才能进行，请先登录");
        }

        // 写操作额外要求用户令牌，防止在仅知 memberId 的情况下代用户下单/改单
        if (sensitivity == Sensitivity.USER_WRITE) {
            String token = context.getUserToken();
            if (token == null || token.isBlank()) {
                log.warn("写操作工具[{}]缺少用户令牌，拒绝调用, memberId={}", toolName, context.getMemberId());
                return Decision.deny("该操作需要有效的登录凭证");
            }
            if (!context.isWriteApproved() || context.getOperationId() == null || context.getOperationId().isBlank()) {
                return Decision.deny("写操作需要先确认具体草稿");
            }
        }

        return Decision.allow();
    }
}
