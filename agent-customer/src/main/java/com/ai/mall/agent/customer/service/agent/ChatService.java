package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.AuditEvent;
import com.ai.mall.agent.customer.model.AuditEventType;
import com.ai.mall.agent.customer.model.ChatRequest;
import com.ai.mall.agent.customer.model.ChatResponse;
import com.ai.mall.agent.customer.model.CollaborationPlan;
import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.model.SourceReference;
import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.service.audit.AuditService;
import com.ai.mall.agent.customer.service.memory.MemoryService;
import com.ai.mall.agent.customer.service.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ReActAgent reactAgent;
    private final AgentCollaborationService agentCollaborationService;
    private final MemoryService memoryService;
    private final AuditService auditService;

    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        log.info("Processing chat request for session: {}", sessionId);

        // 携带用户身份进入工具调用链路，使敏感工具能做鉴权与数据隔离
        ToolInvocationContext context = ToolInvocationContext.builder()
                .sessionId(sessionId)
                .memberId(request.getUserId())
                .userToken(request.getUserToken())
                .build();

        auditService.record(AuditEvent.builder()
                .sessionId(sessionId)
                .memberId(request.getUserId())
                .type(AuditEventType.USER_QUERY.getCode())
                .detail("用户提问: " + request.getMessage())
                .build());

        CollaborationPlan collaboration = agentCollaborationService.plan(request.getMessage());
        String answer = reactAgent.think(sessionId, request.getMessage(), context);

        long responseTime = System.currentTimeMillis() - startTime;

        RagService.RetrievalOutcome evidence = reactAgent.getLastEvidence(sessionId);
        List<SourceReference> sources = isRefusal(answer) ? List.of() : toSources(reactAgent.getLastRetrieval(sessionId));

        return ChatResponse.builder()
                .sessionId(sessionId)
                .message(request.getMessage())
                .answer(answer)
                .intent(classifyIntent(request.getMessage()))
                .responseTime(responseTime)
                .sources(sources)
                .collaboration(collaboration)
                .retrievalDecision(buildRetrievalDecision(answer, sources, evidence))
                .evidenceScore(evidence != null ? evidence.evidenceScore() : null)
                .build();
    }

    /**
     * 检索判定：把"这一轮答案从哪来、依据强不强"翻译成一句话，供界面展示与人工核查。
     * 对应 Adaptive RAG 里的判断环节：依据充分知识库答 / 依据不足转人工 / 实时数据走工具。
     */
    private String buildRetrievalDecision(String answer, List<SourceReference> sources,
                                          RagService.RetrievalOutcome evidence) {
        if (answer != null && answer.contains("无法凭知识库自行确认")) {
            return "检索判定 · 输出护栏拦截，已转人工";
        }
        if (isRefusal(answer)) {
            return evidence != null && evidence.weakEvidence()
                    ? "检索判定 · 依据不足，已自动转人工（证据强度 "
                            + String.format("%.2f", evidence.evidenceScore()) + "）"
                    : "检索判定 · 依据不足，已自动转人工（依据来自历史复答或模型自判）";
        }
        if (sources == null || sources.isEmpty()) {
            return "检索判定 · 实时业务数据（工具直查）";
        }
        return evidence != null && evidence.topSimilarity() > 0
                ? "检索判定 · 依据充分（混合检索，语义相似度 "
                        + String.format("%.2f", evidence.topSimilarity()) + "）"
                : "检索判定 · 依据充分（知识库命中）";
    }

    /**
     * 拒答/转人工话术不展示来源卡片：检索内容与问题无关时给出来源会造成误导，
     * "未找到相关信息"与"这是回答依据"本身也是矛盾的。
     */
    private boolean isRefusal(String answer) {
        return answer != null && (answer.contains("我在知识库中没有找到")
                || answer.contains("无法给您准确的答复"));
    }

    private List<SourceReference> toSources(List<Document> documents) {
        if (documents == null) return List.of();
        List<SourceReference> sources = new ArrayList<>();
        Set<String> seenSources = new HashSet<>();
        for (Document document : documents) {
            if (document.getSource() == null || document.getSource().isBlank()) continue;
            String source = displaySource(document.getSource());
            if (!seenSources.add(source)) continue;
            sources.add(SourceReference.builder()
                    .id(document.getId())
                    .source(source)
                    .type(document.getType())
                    .content(shorten(document.getContent(), 280))
                    .score(document.getScore())
                    .retrievalSource(document.getRetrievalSource() == null ? "hybrid" : document.getRetrievalSource())
                    .build());
            if (sources.size() == 3) break;
        }
        return sources;
    }

    private String classifyIntent(String message) {
        if (message != null && (message.contains("商品") || message.contains("手机") || message.contains("平板")
                || message.contains("耳机") || message.contains("电脑"))) {
            return "product_search";
        }
        if (message != null && (message.contains("订单") || message.contains("物流"))) {
            return "order_task";
        }
        return "general";
    }

    private String shorten(String content, int maxLength) {
        if (content == null) return "";
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "…";
    }

    private String displaySource(String source) {
        if (source == null || source.isBlank() || source.startsWith("docs/")) {
            return source;
        }
        return "docs/knowledge/" + source;
    }

    public void clearSession(String sessionId) {
        memoryService.clearSession(sessionId);
        log.info("Cleared session: {}", sessionId);
    }
}
