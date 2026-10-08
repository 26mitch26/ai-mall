package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次回复的提示词上下文占用快照（字符预算，见 PromptContextBudget）。
 * 供前端展示"上下文占用百分比"与预算内裁剪情况；未组装提示词的确定性路径为 null。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextUsage {
    /** 本会话配置的上下文字符预算上限（防超长安全带，非模型窗口） */
    private int maxCharacters;
    /** 实际组装出的提示词字符数（含指令、知识、历史、工具结果与查询） */
    private int promptCharacters;
    /** 模型上下文窗口 token 上限（占用条的分母） */
    private int contextWindowTokens;
    /** 组装提示词的 token 估算值（CJK 约 1 字 1 token，其余约 4 字符 1 token） */
    private int estimatedTokens;
    /** 必需内容是否在预算内（false 表示必需内容已超限，走拒答） */
    private boolean fits;
    /** 装入提示词的知识库文档段数 */
    private int documentsKept;
    /** 因预算放不下被整段跳过的知识文档数 */
    private int documentsOmitted;
    /** 装入提示词的历史对话条数 */
    private int historyKept;
    /** 因预算放不下被截掉的历史对话条数 */
    private int historyOmitted;
}
