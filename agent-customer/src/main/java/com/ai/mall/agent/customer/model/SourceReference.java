package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条可解释的 RAG 来源，返回给前端展示原文依据。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceReference {
    private String id;
    private String source;
    private String type;
    private String content;
    private double score;
    private String retrievalSource;
    private String version;
    private String contentHash;
    private java.time.Instant effectiveAt;
    private String scope;
    private String knowledgeVersion;
    private boolean evidenceVerified;
}
