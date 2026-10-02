package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 已知缺陷：由失败用例回流沉淀的"可复用失败知识"。
 *
 * <p>失败回流闭环：执行失败 → {@code FailureClassifier} 筛出高价值信号
 * （语义断言失败/服务端 5xx，而非连接等环境噪音）→ {@code TestInsightStore}
 * 按接口聚合计数并持久化 → 下一次执行同一接口再次命中时，
 * 报告标记为"历史已知缺陷（第 N 次复现）"，提示这是待修复的真问题
 * 而非偶发噪音——让每次回归都站在历史经验之上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnownDefect {

    /** 缺陷所在接口，格式：METHOD path，如 "GET /api/orders" */
    private String apiKey;

    /** 缺陷摘要：分类器给出的信号描述 */
    private String summary;

    /** 历史累计命中次数（跨会话持久化） */
    private int occurrences;

    /** 本轮执行是否再次命中该缺陷 */
    private boolean hitThisRun;

    /** 首次发现时间 */
    private LocalDateTime firstSeen;

    /** 最近一次命中时间 */
    private LocalDateTime lastSeen;
}
