package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.ChatRequest;
import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.audit.AuditService;
import com.ai.mall.agent.customer.service.graph.PolicyGraphService;
import com.ai.mall.agent.customer.service.memory.MemoryService;
import com.ai.mall.agent.customer.service.rag.RagService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatEvidenceRegressionTest {
    private final ReActAgent agent = mock(ReActAgent.class);
    private final MemoryService memory = mock(MemoryService.class);
    private final PolicyGraphService graph = mock(PolicyGraphService.class);
    private ChatService service(String answer) {
        when(memory.getShortTermMemory(anyString())).thenReturn(List.of());
        when(graph.retrieve(anyString())).thenReturn(new PolicyGraphService.Result("disabled", List.of(), List.of()));
        when(graph.evidenceFor(any())).thenReturn(List.of());
        when(agent.think(anyString(), anyString(), any())).thenReturn(answer);
        return new ChatService(agent, mock(AgentCollaborationService.class), memory, mock(AuditService.class), graph);
    }
    private Document doc(String source, String content) {
        return Document.builder().id(source).source(source).content(content).version("v1")
                .contentHash("original-source-hash").evidenceVerified(true).build();
    }
    @Test void selectionCannotHideAnotherRetrievedPointLimit() {
        var member = doc("member.md", "积分单笔最高抵扣订单金额的50%。");
        var promotion = doc("promotion.md", "积分单笔最高抵扣订单金额的百分之二十。");
        var service = service(member.getContent());
        when(agent.getLastRetrieval(anyString())).thenReturn(List.of(member));
        when(agent.getLastEvidence(anyString())).thenReturn(new RagService.RetrievalOutcome(List.of(member, promotion), .9, 5, .9, false));
        var response = service.chat(ChatRequest.builder().sessionId("conflict").message("积分最高抵扣多少？").build());
        assertTrue(response.getAnswer().contains("冲突"));
        assertEquals("HANDOFF_RECOMMENDED", response.getResolutionStatus());
        assertEquals("NOT_CONNECTED", response.getHandoffStatus());
        assertEquals(2, response.getSources().size());
        assertFalse(response.getEvidenceReport().conflicts().isEmpty());
    }
    @Test void sourceCardUsesSelectedExcerptWithoutChangingOriginalHash() {
        var original = doc("refund.md", "退款资格：七日内可退货。\n退货操作路径：进入会员中心选择订单。");
        var selected = original.toBuilder().evidenceExcerpt("退货操作路径：进入会员中心选择订单。").build();
        var service = service(selected.getEvidenceExcerpt());
        when(agent.getLastRetrieval(anyString())).thenReturn(List.of(selected));
        var response = service.chat(ChatRequest.builder().sessionId("excerpt").message("退款怎么操作？").build());
        var source = response.getSources().get(0);
        assertEquals("selected-excerpt", source.getContentKind());
        assertEquals(selected.getEvidenceExcerpt(), source.getContent());
        assertEquals(original.getContentHash(), source.getContentHash());
        assertFalse(source.getContent().contains("退款资格"));
        assertFalse(response.getRetrievalDecision().contains("依据充分"));
    }
    @Test void replyWithoutEvidenceDoesNotClaimARealTimeToolWasUsed() {
        var service = service("你好，有什么可以帮助您？");
        when(agent.getLastRetrieval(anyString())).thenReturn(List.of());
        var response = service.chat(ChatRequest.builder().message("你好").build());
        assertTrue(response.getSources().isEmpty());
        assertFalse(response.getRetrievalDecision().contains("工具直查"));
    }
}
