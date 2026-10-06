package com.ai.mall.agent.test.service.quality;

import com.ai.mall.agent.test.model.QualityCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LLM judge：给客服 Agent 的回答打"答案正确性 / 答案忠实性"两分。
 *
 * <p>项目此前的评测只覆盖检索层（Recall@K/MRR）与关键词断言，明确声明
 * "没有测量通用语义 faithfulness，也没有承诺零幻觉"。这个 judge 就是来补那一行的，
 * 但必须诚实地划清边界：
 * <ul>
 *   <li>judge 只在 {@code TEST_AGENT_AI_ENABLED=true} 时存在，默认关闭——现场可复现优先；</li>
 *   <li>judge 自身是 LLM，不构成"零幻觉"的证明，只是把人工逐条判分自动化；</li>
 *   <li>输出解析失败或越界时返回 null（未评分），绝不让工具故障被记成质量缺陷；</li>
 *   <li>忠实性以"检索到的来源"为唯一依据，与人工口径一致：答案必须落在 sources 上。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "test.agent.ai.enabled", havingValue = "true")
public class AgentQualityJudge {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    /**
     * @return 打分结果；无法评分时返回 null
     */
    public JudgeVerdict judge(QualityCase testCase, String answer, List<String> sourceNames) {
        String prompt = buildPrompt(testCase, answer, sourceNames);
        try {
            String raw = chatClientBuilder.build().prompt().user(prompt).call().content();
            return parse(raw);
        } catch (Exception e) {
            log.warn("LLM judge failed for case {}: {}", testCase.getId(), e.getMessage());
            return null;
        }
    }

    private String buildPrompt(QualityCase testCase, String answer, List<String> sourceNames) {
        return """
                你是电商客服 Agent 的评测裁判。请只输出一个 JSON 对象，不要输出任何解释文字或 markdown 代码块。
                字段：
                - correctness: 0-100，回答与参考答案的事实一致度
                - faithfulness: 0-100，回答中的事实是否都能在给定来源中找到支撑
                - reason: 一句话说明扣分原因（中文，不超过 60 字）

                问题：%s
                参考答案：%s
                检索来源：%s
                实际回答：%s
                """.formatted(
                testCase.getQuery(),
                safe(testCase.getReferenceAnswer()),
                sourceNames == null || sourceNames.isEmpty() ? "（无）" : String.join("、", sourceNames),
                safe(answer));
    }

    private JudgeVerdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            Integer correctness = clamp(node, "correctness");
            Integer faithfulness = clamp(node, "faithfulness");
            if (correctness == null || faithfulness == null) {
                log.warn("LLM judge returned incomplete verdict: {}", abbreviate(raw));
                return null;
            }
            return new JudgeVerdict(correctness, faithfulness, node.path("reason").asText(""));
        } catch (Exception e) {
            log.warn("LLM judge output is not parsable: {}", abbreviate(raw));
            return null;
        }
    }

    /** 允许模型把 JSON 包在 ```json 代码块里，这里做一次括号截取。 */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
    }

    private Integer clamp(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        int score = value.asInt();
        return Math.max(0, Math.min(100, score));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "（无）" : value;
    }

    private String abbreviate(String raw) {
        return raw.length() <= 120 ? raw : raw.substring(0, 120) + "...";
    }

    /** judge 打分结果；correctness/faithfulness 均为 0-100。 */
    public record JudgeVerdict(int correctness, int faithfulness, String reason) {
    }
}
