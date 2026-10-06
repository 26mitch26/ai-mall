package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.ChatMessage;
import com.ai.mall.agent.customer.model.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PromptContextBudgetTest {
    @Test void keepsCurrentQueryRulesAndWholeRecentMessages() {
        var old = ChatMessage.builder().role("user").content("旧话题".repeat(1000)).build();
        var recent = ChatMessage.builder().role("user").content("最近的问题").build();
        var packed = PromptContextBudget.pack("规则：写操作必须确认", "当前问题", List.of(), List.of(old, recent), List.of(), 150);
        assertTrue(packed.fits());
        assertTrue(packed.prompt().contains("写操作必须确认"));
        assertTrue(packed.prompt().contains("最近的问题"));
        assertTrue(packed.prompt().contains("当前问题"));
        assertFalse(packed.prompt().contains("旧话题"));
        assertEquals(1, packed.omittedMessages());
        assertTrue(packed.prompt().length() <= 150);
        assertEquals(3000, old.getContent().length()); // persisted history is not modified
    }
    @Test void wholeEvidenceOutranksOptionalHistoryAndDoesNotChangeHash() {
        var doc = Document.builder().source("policy.md").content("条件：普通地区运费8元，偏远地区15元。").contentHash("original-hash").build();
        var packed = PromptContextBudget.pack("规则", "运费问题", List.of(doc),
                List.of(ChatMessage.builder().role("assistant").content("过去回复".repeat(100)).build()), List.of(), 150);
        assertEquals(List.of(doc), packed.documents());
        assertTrue(packed.prompt().contains(doc.getContent()));
        assertEquals("original-hash", doc.getContentHash());
        assertEquals(1, packed.omittedMessages());
    }
    @Test void skipsOversizedEvidenceRatherThanCuttingOffAnException() {
        var huge = Document.builder().source("huge.md").content("规则".repeat(1000) + "例外：不支持退款。").build();
        var small = Document.builder().source("small.md").content("完整政策。").build();
        var packed = PromptContextBudget.pack("规则", "问题", List.of(huge, small), List.of(), List.of(), 150);
        assertEquals(List.of(small), packed.documents());
        assertEquals(1, packed.omittedDocuments());
        assertFalse(packed.prompt().contains("huge.md"));
    }
    @Test void rejectsOversizedRequiredToolJsonAndNeverCutsInstructions() {
        String json = "Observation: {\"code\":200,\"data\":\"" + "内容".repeat(1000) + "\"}";
        var packed = PromptContextBudget.pack("规则：写操作必须确认", "问题", List.of(), List.of(), List.of(json), 150);
        assertFalse(packed.fits());
        assertEquals("", packed.prompt());
    }
    @Test void admitsWholeToolJsonAndMeasuresAnExactCharacterBoundary() {
        var packed = PromptContextBudget.pack("规则", "问题", List.of(), List.of(), List.of("Observation: {\"code\":200}"), 150);
        assertTrue(packed.prompt().endsWith("Observation: {\"code\":200}"));
        int size = packed.prompt().length();
        assertTrue(PromptContextBudget.pack("规则", "问题", List.of(), List.of(), List.of("Observation: {\"code\":200}"), size).fits());
        assertFalse(PromptContextBudget.pack("规则", "问题", List.of(), List.of(), List.of("Observation: {\"code\":200}"), size - 1).fits());
    }
}
