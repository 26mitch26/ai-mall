package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一次客服请求的多 Agent 协作节点，供前端展示路由与交接过程。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCollaboration {
    private String id;
    private String name;
    private String role;
    private String description;
    private String status;
    private boolean active;
}
