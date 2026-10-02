package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话场景用例（数据驱动，来源于 resources/scenarios/customer-agent-scenarios.json）
 * <p>
 * 与 OpenAPI 契约用例（路径/参数/状态码）互补：场景用例验证"对话意图 → 工具调用 → 事实约束 → 安全边界"
 * 的会话级行为。此前"我买了哪些东西答不出来"这类缺口正是契约用例覆盖不到的层次（见实验报告 Failure Case 09）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioCase {

    /** 用例编号（如 conv-001） */
    private String id;

    /** 用例名称（展示用） */
    private String name;

    /** 发送给客服 Agent 的用户话术 */
    private String message;

    /** 鉴权方式：member（携带会员令牌，默认）| none（不带令牌，用于鉴权边界断言） */
    private String auth = "member";

    /** 期望意图（可空：不校验） */
    private String expectedIntent;

    /** 回答必须全部包含的关键词 */
    private List<String> mustContainAll;

    /** 回答至少包含其一的关键词 */
    private List<String> mustContainAny;

    /** 回答不得包含的关键词（如缺槽位引导话术出现在订单清单回答中） */
    private List<String> mustNotContain;

    /** 至少命中的知识来源数（0 = 不校验） */
    private int minSources;

    /** 最大响应耗时毫秒（0 = 不校验） */
    private long maxLatencyMs;

    /** 期望 HTTP 状态码（0 视为 200） */
    private int expectedHttpStatus;
}