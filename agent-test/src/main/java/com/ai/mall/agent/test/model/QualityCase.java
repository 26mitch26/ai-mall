package com.ai.mall.agent.test.model;

import lombok.Data;

import java.util.List;

/**
 * 客服 Agent 质量评测用例（gold）。
 *
 * <p>数据来源与 agent-customer 的 customer-gold.jsonl 同源，逐条复制而来，
 * 以保证两个模块的评测口径一致。核心是 referenceAnswer：它在召回评测脚本里
 * 从未被消费，这里第一次把它接进自动化断言。
 */
@Data
public class QualityCase {

    private String id;

    /** 业务分组，如 shipping-fee */
    private String group;

    /** 类别：policy / no_answer / injection / boundary / conflict */
    private String category;

    /** 数据划分：dev（调参）/ test（评测） */
    private String split;

    private String query;

    /** 标准答案：LLM judge 打 correctness 的唯一依据 */
    private String referenceAnswer;

    /** 期望命中的知识来源文件名 */
    private List<String> relevantSources;

    /** 是否期望拒答（证据不足时） */
    private boolean expectedRefusal;

    /** 期望动作：answer / refuse / clarify / blocked / tool_or_refuse */
    private String expectedAction;

    /** 是否期望被输入净化拦截（注入类用例） */
    private boolean expectedBlocked;

    /** 是否为多轮追问 */
    private boolean followUp;

    /** 多轮上下文（followUp=true 时非空） */
    private List<HistoryTurn> history;

    /** 标注说明（现有评测集为开发者构造，需业务复核） */
    private String annotation;

    @Data
    public static class HistoryTurn {
        private String role;
        private String content;
    }
}
