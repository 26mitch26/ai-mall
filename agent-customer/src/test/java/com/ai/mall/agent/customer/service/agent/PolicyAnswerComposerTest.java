package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PolicyAnswerComposerTest {
    @Test void warrantyExclusionsAndSameCityDurationRemainExplicitInQuotedEvidence() {
        Document warranty = Document.builder().source("warranty.md").content("保修期内非人为原因可免费维修。\n维修流程：提交维修申请。\n以下情形不在保修范围内：人为损坏。")
                .evidenceVerified(true).build();
        assertTrue(PolicyQuestionIntent.matches("保修期内人为损坏会免费修吗？"));
        assertTrue(PolicyAnswerComposer.compose("保修期内人为损坏会免费修吗？", List.of(warranty)).answer().contains("不在保修范围内：人为损坏"));
        Document shipping = Document.builder().source("shipping.md").content("现货24小时发货。\n同城订单支持半日达，最快四小时送达；省内一般一到两天。")
                .evidenceVerified(true).build();
        assertTrue(PolicyQuestionIntent.matches("同城订单呢？"));
        assertTrue(PolicyAnswerComposer.compose("同城订单呢？", List.of(shipping)).answer().contains("同城订单支持半日达"));
        assertTrue(PolicyQuestionIntent.matches("质量问题退货邮费谁承担？"));
    }
    @Test void quotesOnlyVerifiedEvidenceAndReportsOnlyUsedSources() {
        Document payment = Document.builder().source("payment.md").content("#支付\n支付方式：支持微信支付和支付宝。\n其他内容不相关。")
                .evidenceVerified(true).build();
        Document unrelated = Document.builder().source("shipping.md").content("现货24小时发货。")
                .evidenceVerified(true).build();
        var result = PolicyAnswerComposer.compose("支持哪些支付方式", List.of(payment, unrelated));
        assertNotNull(result);
        assertTrue(result.answer().contains("支付方式：支持微信支付和支付宝。"));
        assertFalse(result.answer().contains("24小时"));
        assertEquals(1, result.sources().size());
        assertEquals(payment.getSource(), result.sources().get(0).getSource());
        assertEquals(payment.getContent(), result.sources().get(0).getContent());
        assertNull(payment.getEvidenceExcerpt());
        assertTrue(result.sources().get(0).getEvidenceExcerpt().contains("微信"));
    }

    @Test void doesNotCreateAnInvoiceOrInventRulesFromUnverifiedText() {
        Document invoice = Document.builder().source("invoice.md").content("电子发票在订单完成后自动开具并发送至下单邮箱。")
                .evidenceVerified(true).build();
        var result = PolicyAnswerComposer.compose("帮我获取发票", List.of(invoice));
        assertTrue(result.answer().contains(invoice.getContent()));
        assertFalse(result.answer().contains("已为您生成"));
        invoice.setEvidenceVerified(false);
        assertNull(PolicyAnswerComposer.compose("帮我获取发票", List.of(invoice)));
    }

    @Test void personalOrderAndRefundProgressKeepTheirBusinessRoutes() {
        assertFalse(PolicyQuestionIntent.matches("我的订单支付问题"));
        assertFalse(PolicyQuestionIntent.matches("我的退款进度多久"));
        assertFalse(PolicyQuestionIntent.matches("订单AM-DEMO-123怎么退货"));
    }
}
