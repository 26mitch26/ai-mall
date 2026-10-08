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
import com.ai.mall.agent.customer.service.telemetry.AgentTelemetry;
import com.ai.mall.agent.customer.service.evidence.EvidenceVerifier;
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
    private final com.ai.mall.agent.customer.service.graph.PolicyGraphService policyGraph;
    @org.springframework.beans.factory.annotation.Value("${ai.model.llm.model:qwen3.5-noVL:latest}")
    private String defaultChatModel = "qwen3.5-noVL:latest";

    public ChatResponse chat(ChatRequest request) {
        try (AgentTelemetry.Scope trace = AgentTelemetry.open();
             var modelScope = com.ai.mall.agent.customer.service.llm.RequestModelContext.open(request.getModelConfig(), defaultChatModel)) {
            ChatResponse response = processChat(request);
            response.setTrace(trace.summary());
            response.setSelectedModel(modelScope.selectedModel());
            response.setModelProvider(modelScope.provider());
            response.setUsedModels(modelScope.usedModels());
            response.setGenerationUsed(trace.summary().modelCalls() > 0);
            return response;
        }
    }

    private ChatResponse processChat(ChatRequest request) {
        long startTime = System.currentTimeMillis();

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        String publicSession = sessionId;
        sessionId = memorySession(sessionId, request.getUserId());

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
        String query = StandaloneQueryResolver.resolve(request.getMessage(), memoryService.getShortTermMemory(sessionId));
        var graph = policyGraph.retrieve(query);
        if (!java.util.Objects.equals(query, request.getMessage())) AgentTelemetry.recordStage("rewrite", 0, "success");
        String answer;
        try (var graphEvidence = com.ai.mall.agent.customer.service.graph.GraphEvidenceContext.open(policyGraph.evidenceFor(graph))) {
            answer = reactAgent.think(sessionId, query, context);
        }

        long responseTime = System.currentTimeMillis() - startTime;

        RagService.RetrievalOutcome evidence = reactAgent.getLastEvidence(sessionId);
        EvidenceVerifier.Report evidenceReport = new EvidenceVerifier().verify(answer, reactAgent.getLastRetrieval(sessionId));
        if (evidence != null) {
            var conflicts = new EvidenceVerifier().verify(answer, evidence.documents()).conflicts();
            evidenceReport = new EvidenceVerifier.Report(evidenceReport.method(), evidenceReport.checkedClaims(),
                    evidenceReport.unsupportedNumericClaims(), evidenceReport.claims(), conflicts);
        }
        if(!evidenceReport.conflicts().isEmpty()) {
            answer="检索到的积分抵扣规则存在冲突，暂时无法确认适用上限，请联系人工客服核对。下方列出本次检索的政策依据。";
            AgentTelemetry.recordStage("evidence",0,"refusal");
        }
        List<SourceReference> sources = !evidenceReport.conflicts().isEmpty() && evidence != null
                ? toSources(evidence.documents()) : isRefusal(answer) ? List.of() : toSources(reactAgent.getLastRetrieval(sessionId));
        AgentTelemetry.recordStage("chat", responseTime, isRefusal(answer) ? "refusal" : "success");

        return ChatResponse.builder()
                .sessionId(publicSession)
                .message(request.getMessage())
                .answer(answer)
                .intent(classifyIntent(request.getMessage()))
                .resolutionStatus(HumanSupportIntent.matches(request.getMessage()) || isRefusal(answer)
                        || GreetingIntent.FALLBACK_GUIDANCE_REPEAT.equals(answer)
                        ? "HANDOFF_RECOMMENDED" : "RESPONSE_PROVIDED")
                .handoffStatus("NOT_CONNECTED")
                .responseTime(responseTime)
                .sources(sources)
                .collaboration(collaboration)
                .retrievalDecision(buildRetrievalDecision(answer, sources, evidence))
                .evidenceScore(evidence != null ? evidence.evidenceScore() : null)
                .retrievalRoute(HumanSupportIntent.GUIDANCE.equals(answer) ? "human-guidance"
                        : evidence != null ? evidence.route() : "tool-or-cache")
                .reranker(evidence != null ? evidence.reranker() : null)
                .correctionCount(evidence != null ? evidence.correctionCount() : 0)
                .knowledgeVersion(evidence != null ? evidence.knowledgeVersion() : null)
                .evidenceReport(evidenceReport)
                .graph(graph)
                .contextUsage(reactAgent.getLastContextUsage(sessionId))
                .build();
    }

    public static String memorySession(String sessionId, String memberId) {
        return (memberId == null || memberId.isBlank() ? "anon" : "member_" + memberId) + "_" + sessionId;
    }

    /**
     * 检索判定：把"这一轮答案从哪来、依据强不强"翻译成一句话，供界面展示与人工核查。
     * 对应 Adaptive RAG 里的判断环节：依据充分知识库答 / 依据不足转人工 / 实时数据走工具。
     */
    private String buildRetrievalDecision(String answer, List<SourceReference> sources,
                                          RagService.RetrievalOutcome evidence) {
        if (GreetingIntent.ANSWER.equals(answer)) return "寒暄问候 · 直接回复，未走检索与生成";
        if (GreetingIntent.isIdentityReply(answer)) return "身份说明 · 已结合登录状态个性化回复，未走检索与生成";
        if (GreetingIntent.CHAT_ANSWER.equals(answer)) return "闲聊陪聊 · 直接回复，未走检索与生成";
        if (GreetingIntent.FALLBACK_GUIDANCE_FIRST.equals(answer)) return "闲聊引导 · 超出业务范围，给出能力菜单";
        if (GreetingIntent.FALLBACK_GUIDANCE_REPEAT.equals(answer)) return "闲聊引导 · 重复未命中，建议更换问法或转人工";
        if (HumanSupportIntent.GUIDANCE.equals(answer)) return "处理指引 · 人工客服尚未接入";
        if (answer != null && answer.contains("无法凭知识库自行确认")) {
            return "检索判定 · 输出护栏拦截，建议联系人工客服";
        }
        if (isRefusal(answer)) {
            return evidence != null && evidence.weakEvidence()
                    ? "检索判定 · 依据不足，建议联系人工客服（证据强度 "
                            + String.format("%.2f", evidence.evidenceScore()) + "）"
                    : "检索判定 · 依据不足，建议联系人工客服（依据来自历史复答或模型自判）";
        }
        if (sources == null || sources.isEmpty()) {
            return "已提供回复；未展示政策引用，请按具体业务页面核对";
        }
        String routeLabel = evidence == null ? "知识检索" : switch (String.valueOf(evidence.route())) {
            case "semantic" -> "语义检索";
            case "exact" -> "关键词检索";
            case "hybrid" -> "混合检索";
            default -> "知识检索";
        };
        return "已找到政策依据（" + routeLabel + "），请核对适用条件";
    }

    /**
     * 普通拒答不展示来源卡片；冲突拒答保留检索依据供人工核对。
     * 检索内容与问题无关时给出来源会造成误导，
     * "未找到相关信息"与"这是回答依据"本身也是矛盾的。
     */
    private boolean isRefusal(String answer) {
        return answer != null && (answer.contains("我在知识库中没有找到")
                || answer.contains("无法给您准确的答复")
                || answer.contains("暂时无法确认适用上限"));
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
                    .content(shorten(document.getEvidenceExcerpt() == null ? document.getContent() : document.getEvidenceExcerpt(), 280))
                    .contentKind(document.getEvidenceExcerpt() == null ? "source-preview" : "selected-excerpt")
                    .score(document.getScore())
                    .retrievalSource(document.getRetrievalSource() == null ? "hybrid" : document.getRetrievalSource())
                    .version(document.getVersion())
                    .contentHash(document.getContentHash())
                    .effectiveAt(document.getEffectiveAt())
                    .scope(document.getScope())
                    .evidenceVerified(document.isEvidenceVerified())
                    .knowledgeVersion(document.getKnowledgeVersion())
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
