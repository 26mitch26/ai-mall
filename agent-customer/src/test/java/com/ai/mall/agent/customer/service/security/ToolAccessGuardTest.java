package com.ai.mall.agent.customer.service.security;

import com.ai.mall.agent.customer.model.ToolInvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具鉴权测试
 * <p>
 * 核心目标：证明"未登录用户拿不到他人订单数据、也无法代他人创建工单"。
 */
class ToolAccessGuardTest {

    private final ToolAccessGuard guard = new ToolAccessGuard();

    @Test
    @DisplayName("公开工具允许匿名调用")
    void shouldAllowPublicToolForAnonymous() {
        ToolAccessGuard.Decision decision =
                guard.authorize("search_products", ToolInvocationContext.anonymous("s1"));

        assertTrue(decision.isAllowed());
    }

    @Test
    @DisplayName("敏感工具未登录时被拒绝（fail-closed）")
    void shouldDenyUserDataToolWhenAnonymous() {
        ToolAccessGuard.Decision decision =
                guard.authorize("get_order_info", ToolInvocationContext.anonymous("s1"));

        assertFalse(decision.isAllowed(), "未登录不得查询订单，否则可用订单号遍历他人数据");
        assertTrue(decision.getReason().contains("登录"));
    }

    @Test
    @DisplayName("敏感工具缺少上下文时同样被拒绝")
    void shouldDenyUserDataToolWhenContextMissing() {
        assertFalse(guard.authorize("get_order_info", null).isAllowed());
        assertFalse(guard.authorize("create_after_sale", null).isAllowed());
    }

    @Test
    @DisplayName("已登录用户可查询自己的订单")
    void shouldAllowUserDataToolWhenAuthenticated() {
        ToolInvocationContext context = ToolInvocationContext.builder()
                .sessionId("s1").memberId("member-1").build();

        assertTrue(guard.authorize("get_order_info", context).isAllowed());
    }

    @Test
    @DisplayName("订单列表工具同样按用户数据分级：未登录拒绝、已登录放行")
    void shouldGuardMyOrdersListToolByLogin() {
        assertFalse(guard.authorize("list_my_orders", ToolInvocationContext.anonymous("s1")).isAllowed(),
                "未登录不得列出会员订单");

        ToolInvocationContext context = ToolInvocationContext.builder()
                .sessionId("s1").memberId("member-1").build();
        assertTrue(guard.authorize("list_my_orders", context).isAllowed());
    }

    @Test
    @DisplayName("下单工具为写操作：未登录拒绝、仅有 memberId 拒绝、带令牌放行")
    void shouldRequireTokenForPlaceOrder() {
        assertFalse(guard.authorize("place_order", ToolInvocationContext.anonymous("s1")).isAllowed(),
                "未登录不得代用户下单");

        ToolInvocationContext noToken = ToolInvocationContext.builder()
                .sessionId("s1").memberId("member-1").build();
        assertFalse(guard.authorize("place_order", noToken).isAllowed(),
                "仅知道 memberId 不足以代用户下单");

        ToolInvocationContext withToken = ToolInvocationContext.builder()
                .sessionId("s1").memberId("member-1").userToken("jwt-xxx").build();
        assertTrue(guard.authorize("place_order", withToken).isAllowed());
    }

    @Test
    @DisplayName("取消订单同样为写操作：未登录/无令牌拒绝、带令牌放行")
    void shouldRequireTokenForCancelOrder() {
        assertFalse(guard.authorize("cancel_order", ToolInvocationContext.anonymous("s1")).isAllowed(),
                "未登录不得代用户取消订单");

        ToolInvocationContext noToken = ToolInvocationContext.builder()
                .sessionId("s1").memberId("member-1").build();
        assertFalse(guard.authorize("cancel_order", noToken).isAllowed(),
                "仅知道 memberId 不足以取消订单");

        ToolInvocationContext withToken = ToolInvocationContext.builder()
                .sessionId("s1").memberId("member-1").userToken("jwt-xxx").build();
        assertTrue(guard.authorize("cancel_order", withToken).isAllowed());
    }

    @Test
    @DisplayName("写操作缺少用户令牌时被拒绝")
    void shouldDenyWriteToolWithoutToken() {
        ToolInvocationContext context = ToolInvocationContext.builder()
                .sessionId("s1").memberId("member-1").build();

        ToolAccessGuard.Decision decision = guard.authorize("create_after_sale", context);

        assertFalse(decision.isAllowed(), "仅知道 memberId 不足以代用户创建工单");
    }

    @Test
    @DisplayName("写操作带用户令牌时放行")
    void shouldAllowWriteToolWithToken() {
        ToolInvocationContext context = ToolInvocationContext.builder()
                .sessionId("s1").memberId("member-1").userToken("jwt-xxx").build();

        assertTrue(guard.authorize("create_after_sale", context).isAllowed());
    }

    @Test
    @DisplayName("未登记的工具一律拒绝，防止模型幻觉调用")
    void shouldDenyUnregisteredTool() {
        ToolInvocationContext context = ToolInvocationContext.builder()
                .sessionId("s1").memberId("member-1").userToken("jwt-xxx").build();

        assertFalse(guard.authorize("delete_all_orders", context).isAllowed());
        assertFalse(guard.authorize("", context).isAllowed());
        assertFalse(guard.authorize(null, context).isAllowed());
    }
}
