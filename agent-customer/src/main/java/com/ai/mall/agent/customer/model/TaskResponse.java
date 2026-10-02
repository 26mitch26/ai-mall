package com.ai.mall.agent.customer.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 任务型对话的可解释执行结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResponse {
    private String sessionId;
    private String task;
    private String taskName;
    private String dpmAction;
    private String nextPrompt;
    private boolean completed;
    private Map<String, Object> dstState;
    private String apiCall;
    private String result;
}
