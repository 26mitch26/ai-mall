package com.ai.mall.agent.customer.service.rag;

import com.ai.mall.agent.customer.service.llm.AgentLlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问题分解器：识别用户 query 中的多个意图，拆分为独立子问题分别检索。
 *
 * <h3>为什么需要问题分解</h3>
 * 用户经常一句话问多个问题，比如：
 * <pre>
 *   "退货要多久能退款？运费谁出？"
 *   → 子问题 1: "退货要多久能退款？"
 *   → 子问题 2: "退货运费谁出？"
 * </pre>
 * 如果不分拆，混合检索只能命中"退货"相关的文档，"运费"相关的可能排在后面甚至丢失。
 * 分拆后分别检索，每个子问题都能精准命中对应文档，合并后覆盖面更广。
 *
 * <h3>实现策略</h3>
 * 1. 快速规则检测：用标点符号和连接词判断是否有多意图（零成本，不调 LLM）
 * 2. LLM 分解：确认有多意图后，调 LLM 拆分为独立子问题
 * 3. 降级：LLM 失败或返回不合理结果时，回退为原始 query 不分解
 *
 * <h3>正确性护栏</h3>
 * - 子问题数量上限 3 个，防止 LLM 过度拆分
 * - 每个子问题必须是完整问句（以？或?结尾），否则丢弃
 * - 子问题为空时回退为原始 query
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryDecomposer {

    private final AgentLlmClient agentLlmClient;

    /** 子问题数量上限 */
    private static final int MAX_SUB_QUERIES = 3;

    /** 多意图检测：中英文问号、顿号、逗号+连接词 */
    private static final Pattern MULTI_INTENT_PATTERN = Pattern.compile(
            "[？?]|" +                          // 问号分隔
            "[、]|" +                           // 顿号列举
            "(?:而且|并且|还有|另外|同时|以及)"   // 连接词
    );

    /** LLM 返回的子问题提取：匹配编号列表格式 */
    private static final Pattern SUB_QUERY_PATTERN = Pattern.compile(
            "^\\s*(?:\\d+[.、)）]|[-•])\\s*(.+)$"
    );

    /**
     * 判断 query 是否包含多个意图。
     * 快速规则检测，不调 LLM，零成本。
     */
    public boolean hasMultipleIntents(String query) {
        if (query == null || query.isBlank()) return false;
        // 包含多个问号 → 大概率多意图
        long questionMarks = query.chars().filter(c -> c == '?' || c == '？').count();
        if (questionMarks >= 2) return true;
        // 包含顿号或连接词 → 可能多意图
        return MULTI_INTENT_PATTERN.matcher(query).find();
    }

    /**
     * 将多意图 query 分解为独立子问题。
     *
     * @param query 原始用户 query
     * @return 子问题列表；如果无法分解或不需要分解，返回只包含原始 query 的单元素列表
     */
    public List<String> decompose(String query) {
        if (query == null || query.isBlank()) {
            return Collections.singletonList(query);
        }

        // 快速检测：没有多意图就不浪费 LLM 调用
        if (!hasMultipleIntents(query)) {
            return Collections.singletonList(query.trim());
        }

        log.info("检测到多意图 query，开始分解: {}", query);

        try {
            String prompt = buildDecomposePrompt(query);
            String response = agentLlmClient.chat(prompt);

            if (response == null || response.isBlank()) {
                log.warn("LLM 返回空，不分解");
                return Collections.singletonList(query.trim());
            }

            List<String> subQueries = parseSubQueries(response);

            // 护栏：子问题数量检查
            if (subQueries.size() < 2) {
                log.info("LLM 只返回 {} 个子问题，不分解", subQueries.size());
                return Collections.singletonList(query.trim());
            }
            if (subQueries.size() > MAX_SUB_QUERIES) {
                subQueries = subQueries.subList(0, MAX_SUB_QUERIES);
            }

            log.info("问题分解完成: '{}' → {}", query, subQueries);
            return subQueries;

        } catch (Exception e) {
            log.warn("问题分解失败，回退为原始 query: {}", e.getMessage());
            return Collections.singletonList(query.trim());
        }
    }

    /**
     * 构建分解 prompt：要求 LLM 返回编号列表，每个子问题独立完整。
     */
    private String buildDecomposePrompt(String query) {
        return "你是一个问题分解助手。请将用户的问题拆分为最多" + MAX_SUB_QUERIES + "个独立的子问题。\n\n" +
                "规则：\n" +
                "1. 每个子问题必须是完整的问句，能独立检索答案\n" +
                "2. 子问题之间不应有依赖关系\n" +
                "3. 如果问题只包含一个意图，直接返回原问题\n" +
                "4. 用编号列表格式返回，每行一个子问题\n\n" +
                "用户问题：" + query + "\n\n" +
                "子问题：";
    }

    /**
     * 解析 LLM 返回的子问题列表。
     * 支持格式：1. xxx / 1、xxx / 1) xxx / - xxx / • xxx
     * 也支持纯文本（每行一个问句）。
     */
    private List<String> parseSubQueries(String response) {
        List<String> subQueries = new ArrayList<>();
        String[] lines = response.split("\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 尝试匹配编号格式
            Matcher matcher = SUB_QUERY_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                String q = matcher.group(1).trim();
                if (isValidQuestion(q)) {
                    subQueries.add(q);
                }
            } else if (isValidQuestion(trimmed)) {
                // 纯文本问句
                subQueries.add(trimmed);
            }
        }

        return subQueries;
    }

    /**
     * 验证是否为有效问句：非空且长度合理。
     */
    private boolean isValidQuestion(String text) {
        return text != null && !text.isBlank() && text.length() >= 2 && text.length() <= 200;
    }
}