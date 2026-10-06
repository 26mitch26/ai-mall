package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {
    private String id;
    private String content;
    private String source;
    private String type;
    private double score;
    private String retrievalSource;
    private List<String> keywords;
    private double[] embedding;
    /** Published knowledge revision that produced this evidence. */
    private String version;
    /** SHA-256 of the original source content. */
    private String contentHash;
    /** Inclusive policy effective time, when supplied. */
    private java.time.Instant effectiveAt;
    /** Optional audience/tenant scope. */
    private String scope;
    /** Global published knowledge snapshot observed when retrieval completed. */
    private String knowledgeVersion;
    /** Set only when retrieval has validated the currently published revision. */
    private boolean evidenceVerified;
}
