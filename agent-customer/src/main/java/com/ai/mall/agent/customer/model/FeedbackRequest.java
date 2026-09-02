package com.ai.mall.agent.customer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户反馈请求（人工校对闭环）
 * <p>
 * 用户对客服回答点赞/点踩，并可给出"正确答案"。
 * 这些样本是后续评测幻觉率、优化提示词与知识库的ground truth。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackRequest {

    /** 会话ID */
    private String sessionId;

    /** 被反馈的回答所属消息ID（可选） */
    private String messageId;

    /**
     * 评价：up（有用）/ down（没用或答错）
     */
    private String rating;

    /** 用户认为正确的答案（点踩时填写，作为人工校对样本） */
    private String correctedAnswer;

    /** 补充说明 */
    private String comment;
}
