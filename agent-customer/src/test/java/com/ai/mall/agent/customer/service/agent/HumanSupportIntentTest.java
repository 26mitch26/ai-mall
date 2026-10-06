package com.ai.mall.agent.customer.service.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HumanSupportIntentTest {
    @Test void respectsCustomerControlWithoutClaimingTransfer() {
        for (String query : new String[]{"请转人工", "找人工客服", "真人客服在哪", "can I speak to a person", "need human support"}) {
            assertTrue(HumanSupportIntent.matches(query), query);
        }
        assertFalse(HumanSupportIntent.GUIDANCE.contains("已转接"));
        assertFalse(HumanSupportIntent.GUIDANCE.contains("已创建"));
        assertTrue(HumanSupportIntent.GUIDANCE.contains("尚未接入"));
    }

    @Test void doesNotHijackPolicyQuestionsOrNegativeRequests() {
        for (String query : new String[]{"不要转人工，我只想知道运费", "不想找人工客服", "don't need a human agent", "退款政策是什么", "如何申请退货"}) {
            assertFalse(HumanSupportIntent.matches(query), query);
        }
    }
}
