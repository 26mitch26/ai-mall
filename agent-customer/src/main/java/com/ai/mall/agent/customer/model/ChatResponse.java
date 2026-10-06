package com.ai.mall.agent.customer.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String sessionId;
    private String message;
    private String answer;
    private String intent;
    /** Explicit handling state; does not certify semantic correctness or business completion. */
    private String resolutionStatus;
    private String handoffStatus;
    private Long responseTime;
    private List<SourceReference> sources;
    private CollaborationPlan collaboration;
    /** 检索判定结论，如"依据充分 · 知识库命中（语义相似度 0.62）"，供前端展示可解释性 */
    private String retrievalDecision;
    /** 证据强度（top-1 语义相似度为主信号，0~1），仅 RAG 路径有值 */
    private Double evidenceScore;
    private String retrievalRoute;
    private String reranker;
    private Integer correctionCount;
    private String knowledgeVersion;
    private com.ai.mall.agent.customer.service.telemetry.AgentTelemetry.Summary trace;
    private com.ai.mall.agent.customer.service.evidence.EvidenceVerifier.Report evidenceReport;
    private com.ai.mall.agent.customer.service.graph.PolicyGraphService.Result graph;
}
