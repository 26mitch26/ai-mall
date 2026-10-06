package com.ai.mall.agent.customer.service.rag;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.agent.PolicyAnswerComposer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PolicySectionChunkerTest {
    @Test void wrappedParagraphIsNotSplitInTheMiddleOfItsRule() {
        String paragraph="七日退货需要商品保持完整\n且保留原包装并提交申请。";
        var chunks=PolicySectionChunker.chunk("# 规则\n"+paragraph,16);
        assertEquals(1,chunks.size()); assertTrue(chunks.get(0).contains(paragraph));
    }
    @Test void preservesRuleAndExceptionAcrossSoftBoundary() {
        String rule="签收七日内可退货。".repeat(20);
        String exception="例外：食品和定制商品不适用。";
        var chunks=PolicySectionChunker.chunk("# 退货\n"+rule+"\n\n"+exception+"\n其他独立规则。",128);
        assertTrue(chunks.stream().anyMatch(c -> c.contains(rule)&&c.contains(exception)));
        assertTrue(chunks.stream().anyMatch(c -> c.length()>128));
    }
    @Test void sourceHeadingContextIsPresentAndTopicsAreNotMerged() {
        var chunks=PolicySectionChunker.chunk("# 商城政策\n## 退款\n退款规则甲。\n退款规则乙。\n## 保修\n保修规则丙。",24);
        assertTrue(chunks.stream().allMatch(c -> c.startsWith("# 商城政策\n## ")));
        assertFalse(chunks.stream().anyMatch(c -> c.contains("退款规则")&&c.contains("保修规则")));
    }
    @Test void tablesAndCodeBlocksRemainWhole() {
        String table="|地区|费用|\n|---|---|\n|普通|8元|\n|偏远|15元|";
        String code="```text\n不要拆开这组完整内容\n第二行\n```";
        var chunks=PolicySectionChunker.chunk("# 手册\n"+table+"\n"+code,16);
        assertTrue(chunks.stream().anyMatch(c -> c.contains(table)));
        assertTrue(chunks.stream().anyMatch(c -> c.contains(code)));
    }
    @Test void metadataLinksAndOriginalDocumentStayIntact() {
        var doc=Document.builder().id("policy").source("policy.md").content("# 政策\n## 退款\n退款规则。\n## 保修\n保修规则。").contentHash("original").build();
        var chunks=RagService.DocumentChunker.chunkWithMetadata(doc,"policy_section",20,0);
        assertEquals("original",doc.getContentHash());
        assertEquals("policy",chunks.get(0).getDocId());
        assertEquals(chunks.get(1).getChunkId(),chunks.get(0).getNextChunkId());
        assertEquals(chunks.get(0).getChunkId(),chunks.get(1).getPrevChunkId());
    }
    @Test void answerExtractionCannotDiscardAttachedException() {
        var doc=Document.builder().source("refund.md").evidenceVerified(true)
                .content("# 退货\n七日内支持退货。\n但食品和定制商品不适用。").build();
        var result=PolicyAnswerComposer.compose("退货政策",List.of(doc));
        assertNotNull(result); assertTrue(result.answer().contains("但食品和定制商品不适用"));
    }
    @Test void oversizedEvidenceIsNotSilentlyTruncatedBeforeException() {
        var doc=Document.builder().source("refund.md").evidenceVerified(true)
                .content("退货规则："+"说明文字".repeat(200)+"。\n但食品不适用。").build();
        assertNull(PolicyAnswerComposer.compose("退货政策",List.of(doc)));
    }
    @Test void emptyInputsAreHandled() {
        assertTrue(PolicySectionChunker.chunk(null,512).isEmpty());
        assertTrue(PolicySectionChunker.evidenceUnits(" ").isEmpty());
    }
    @Test void combinedFacetsFromSameSourceKeepBothRetrievedEvidenceBodies() {
        var refund=Document.builder().id("chunk1").source("policy.md").version("v1").contentHash("parent-hash")
                .evidenceVerified(true).content("退款时效：审核后3个工作日到账。").build();
        var invoice=refund.toBuilder().id("chunk2").content("电子发票发送至下单邮箱。").build();
        var result=PolicyAnswerComposer.compose("退款规则和发票服务",List.of(refund,invoice));
        assertNotNull(result); assertEquals(1,result.sources().size());
        assertTrue(result.sources().get(0).getContent().contains(invoice.getContent()));
        assertEquals("parent-hash",result.sources().get(0).getContentHash());
        assertEquals(0,new com.ai.mall.agent.customer.service.evidence.EvidenceVerifier().verify(result.answer(),result.sources()).unsupportedNumericClaims());
    }
}
