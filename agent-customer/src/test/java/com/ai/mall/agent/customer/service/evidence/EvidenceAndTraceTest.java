package com.ai.mall.agent.customer.service.evidence;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.model.ChatMessage;
import com.ai.mall.agent.customer.service.agent.StandaloneQueryResolver;
import com.ai.mall.agent.customer.service.telemetry.AgentTelemetry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceAndTraceTest {
    @Test void quotationCheckDoesNotClaimSemanticEntailment() {
        var docs=List.of(Document.builder().id("shipping").content("普通地区运费8元。").build());
        var report=new EvidenceVerifier().verify("普通地区运费8元。偏远地区运费99元。",docs);
        assertEquals(1,report.unsupportedNumericClaims());
        assertEquals("lexical-match",report.claims().get(0).support());
        assertEquals("unverified",report.claims().get(1).support());
    }
    @Test void conflictingPointLimitsAreReported() {
        var docs=List.of(Document.builder().id("promotion").content("积分单笔最高抵扣订单金额的百分之二十。").build(),
                Document.builder().id("member").content("积分单笔最高抵扣订单金额的50%。").build());
        assertEquals(1,new EvidenceVerifier().verify("积分最多抵扣50%。",docs).conflicts().size());
    }
    @Test void followUpUsesUserQuestionAndLeavesIndependentQuestionUntouched() {
        ChatMessage user=new ChatMessage();user.setRole("user");user.setContent("质量问题可以退货吗？");
        ChatMessage bot=new ChatMessage();bot.setRole("assistant");bot.setContent("随意编造事实");
        assertTrue(StandaloneQueryResolver.resolve("那运费呢？",List.of(user,bot)).startsWith(user.getContent()));
        assertEquals("发票怎么开？",StandaloneQueryResolver.resolve("发票怎么开？",List.of(user,bot)));
    }
    @Test void tracesAreRequestLocalAndBudgetBlocksExtraGeneration() {
        try(var scope=AgentTelemetry.open(1)) {
            assertTrue(AgentTelemetry.reserveModelCall());assertFalse(AgentTelemetry.reserveModelCall());
            AgentTelemetry.recordLlm(12,3,4,"success");assertEquals(1,scope.summary().modelCalls());
        }
        try(var scope=AgentTelemetry.open(0)) {
            assertFalse(AgentTelemetry.reserveModelCall());assertEquals(0,scope.summary().modelCalls());assertTrue(scope.summary().stages().isEmpty());
        }
    }
}
