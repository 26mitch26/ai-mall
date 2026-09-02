package com.ai.mall.agent.customer.service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 输出护栏单元测试
 * <p>
 * 覆盖"防乱说"的四类核心场景：空回答、内部标记泄露、无事实来源的具体事实、敏感信息脱敏。
 */
class OutputGuardrailTest {

    private final OutputGuardrail guardrail = new OutputGuardrail(new SensitiveDataMasker());

    @Test
    @DisplayName("空回答应被拦截并返回兜底话术")
    void shouldBlockEmptyAnswer() {
        OutputGuardrail.GuardrailResult nullResult = guardrail.check(null, true);
        assertFalse(nullResult.isAllowed());
        assertEquals(OutputGuardrail.RiskType.EMPTY, nullResult.getRiskType());
        assertEquals(OutputGuardrail.NO_ANSWER_FALLBACK, nullResult.getAnswer());

        OutputGuardrail.GuardrailResult blankResult = guardrail.check("   ", false);
        assertFalse(blankResult.isAllowed());
        assertEquals(OutputGuardrail.RiskType.EMPTY, blankResult.getRiskType());
    }

    @Test
    @DisplayName("泄露ReAct内部推理结构应被拦截")
    void shouldBlockInternalMarkerLeak() {
        String leaked = "Thought: 用户问订单\nFinal Answer: 您的订单已发货。";

        OutputGuardrail.GuardrailResult result = guardrail.check(leaked, true);

        assertFalse(result.isAllowed(), "即便有事实来源，泄露推理结构也必须拦截");
        assertEquals(OutputGuardrail.RiskType.INTERNAL_MARKER_LEAK, result.getRiskType());
        assertEquals(OutputGuardrail.INTERNAL_LEAK_FALLBACK, result.getAnswer());
        assertFalse(result.getAnswer().contains("Thought"));
    }

    @Test
    @DisplayName("无事实来源却给出具体金额应被拦截")
    void shouldBlockUnsourcedAmount() {
        OutputGuardrail.GuardrailResult result =
                guardrail.check("您的订单金额是199元，已为您退款。", false);

        assertFalse(result.isAllowed());
        assertEquals(OutputGuardrail.RiskType.UNSOURCED_FACT, result.getRiskType());
        assertEquals(OutputGuardrail.UNSOURCED_FACT_FALLBACK, result.getAnswer());
        assertFalse(result.getAnswer().contains("199"));
    }

    @Test
    @DisplayName("无事实来源却给出订单号应被拦截")
    void shouldBlockUnsourcedOrderNo() {
        OutputGuardrail.GuardrailResult result =
                guardrail.check("您的订单号是2024010100001，已安排发货。", false);

        assertFalse(result.isAllowed());
        assertEquals(OutputGuardrail.RiskType.UNSOURCED_FACT, result.getRiskType());
    }

    @Test
    @DisplayName("有事实来源时具体金额应放行")
    void shouldAllowGroundedConcreteFact() {
        String answer = "您的订单金额是199元。";

        OutputGuardrail.GuardrailResult result = guardrail.check(answer, true);

        assertTrue(result.isAllowed());
        assertEquals(OutputGuardrail.RiskType.NONE, result.getRiskType());
        assertEquals(answer, result.getAnswer());
    }

    @Test
    @DisplayName("正常闲聊与流程说明不应被误伤")
    void shouldAllowNormalAnswer() {
        assertPass(guardrail.check("您好，请问有什么可以帮您？", false));
        assertPass(guardrail.check("我们支持7天无理由退货，15天换货服务。", false));
        assertPass(guardrail.check("您的订单123456目前状态为已发货。", false));
        assertPass(guardrail.check("退款一般会在3到7个工作日内到账。", false));
    }

    @Test
    @DisplayName("敏感信息应脱敏后放行")
    void shouldMaskSensitiveInfo() {
        OutputGuardrail.GuardrailResult phoneResult =
                guardrail.check("您可以拨打13812345678联系我们。", false);

        assertTrue(phoneResult.isAllowed(), "敏感信息应脱敏放行，而非整体拒绝");
        assertEquals(OutputGuardrail.RiskType.SENSITIVE_INFO_MASKED, phoneResult.getRiskType());
        assertTrue(phoneResult.getAnswer().contains("138****5678"));
        assertFalse(phoneResult.getAnswer().contains("13812345678"));

        OutputGuardrail.GuardrailResult idResult =
                guardrail.check("您的身份证号为110101199003071234，已登记。", true);

        assertTrue(idResult.isAllowed());
        assertTrue(idResult.getAnswer().contains("110101********1234"));
        assertFalse(idResult.getAnswer().contains("110101199003071234"));

        OutputGuardrail.GuardrailResult emailResult =
                guardrail.check("已发送邮件至zhangsan@example.com，请查收。", true);

        assertTrue(emailResult.isAllowed());
        assertTrue(emailResult.getAnswer().contains("z***@example.com"));
        assertFalse(emailResult.getAnswer().contains("zhangsan@example.com"));
    }

    @Test
    @DisplayName("超长回答应截断后放行")
    void shouldTruncateTooLongAnswer() {
        String tooLong = "啊".repeat(4001);

        OutputGuardrail.GuardrailResult result = guardrail.check(tooLong, false);

        assertTrue(result.isAllowed());
        assertEquals(OutputGuardrail.RiskType.TRUNCATED, result.getRiskType());
        assertEquals(4000, result.getAnswer().length());
    }

    private void assertPass(OutputGuardrail.GuardrailResult result) {
        assertTrue(result.isAllowed(), "不应拦截: " + result.getReason());
    }
}
