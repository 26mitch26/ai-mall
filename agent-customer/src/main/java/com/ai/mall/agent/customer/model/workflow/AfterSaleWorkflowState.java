package com.ai.mall.agent.customer.model.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** Persisted, owner-scoped checkpoint for an after-sale request. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSaleWorkflowState {
    private String taskId;
    private String workflowType;
    private String ownerId;
    private String sessionId;
    private String orderSn;
    private String reason;
    private String description;
    private String requestHash;
    private String orderSummary;
    private String policyAnswer;
    private List<String> policySourceIds;
    private List<String> policySourceVersions;
    private boolean policyEvidenceWeak;
    private String draft;
    private String status;
    private long version;
    private String operationId;
    private String result;
    private Instant createdAt;
    private Instant updatedAt;
}
