package com.ai.mall.agent.customer.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 接待分流 Agent 输出的协作计划。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollaborationPlan {
    private String routeSummary;
    private String activeAgent;
    private List<AgentCollaboration> agents;
}
