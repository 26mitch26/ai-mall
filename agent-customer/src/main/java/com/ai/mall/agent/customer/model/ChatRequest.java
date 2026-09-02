package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String sessionId;
    private String message;
    private String userId;

    /**
     * 用户 JWT，调用敏感工具（订单查询、创建工单）时透传给后端做真实身份校验。
     * 仅存在于内存调用链中，不写入会话记忆与审计流水。
     */
    private String userToken;
}
