package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 任务型对话请求：由 DST 提取槽位，再由 DPM 决定澄清或调用工具。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskRequest {
    private String sessionId;
    private String message;
    private String userId;
    private String userToken;
}
