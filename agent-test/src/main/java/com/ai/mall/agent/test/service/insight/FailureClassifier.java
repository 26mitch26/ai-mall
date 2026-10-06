package com.ai.mall.agent.test.service.insight;

import com.ai.mall.agent.test.model.AssertionDetail;
import com.ai.mall.agent.test.model.TestCase;
import com.ai.mall.agent.test.model.TestResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 失败分类器：从失败结果中筛出"值得沉淀"的高价值信号。
 *
 * <p>并非所有失败都应回流——失败可能是服务缺陷、期望值偏差，也可能是环境噪音
 * （目标离线、网络超时）。盲目沉淀会把噪音固化进经验库。规则：
 * <ul>
 *   <li>语义断言失败（业务码吞错 / Schema 违规 / 分页破坏）：高置信疑似缺陷，沉淀；</li>
 *   <li>服务端 5xx：接口故障，沉淀；</li>
 *   <li>连接类失败：环境噪音，不沉淀；</li>
 *   <li>纯期望偏差：可能是契约/实现差异而非缺陷，信号不足，不沉淀。</li>
 * </ul>
 */
@Slf4j
@Service
public class FailureClassifier {

    private static final List<String> SEMANTIC_ASSERTIONS = List.of(
            "Business Code Check (CommonResult)",
            "OpenAPI Schema Check",
            "Pagination Invariant Check (total >= list.size)"
    );

    private static final int MAX_SUMMARY_LENGTH = 200;

    public Optional<String> classify(TestCase testCase, TestResult result) {
        if (testCase == null || result == null || result.isPassed()) {
            return Optional.empty();
        }
        if (isConnectionNoise(result)) {
            return Optional.empty();
        }

        Optional<String> semantic = failedSemanticAssertion(result);
        if (semantic.isPresent()) {
            return semantic;
        }
        if (result.getActualStatusCode() >= 500) {
            return Optional.of("接口返回 HTTP " + result.getActualStatusCode()
                    + "（服务端错误，疑似服务缺陷）");
        }
        // 精确状态码偏差等：期望值本身可能来自估算，信号不足，不沉淀
        return Optional.empty();
    }

    /**
     * 会话/评测类结果的失败分类。
     *
     * <p>契约层靠断言名白名单挑信号（{@link #SEMANTIC_ASSERTIONS}），但会话场景与质量评测
     * 的断言名是动态拼出来的（如"回答包含[退货]"），不可能进白名单。因此这里改用另一条规则：
     * <b>除环境噪音外，一律视为高价值信号</b>——意图识别错、来源缺失、拒答失效、
     * 注入未被阻断，都是产品缺陷而非实现细节。
     *
     * @param result 会话/评测用例结果
     * @return 可沉淀的失败摘要；噪音或通过时为空
     */
    public Optional<String> classifyConversational(TestResult result) {
        if (result == null || result.isPassed() || isConnectionNoise(result)) {
            return Optional.empty();
        }
        String summary = failedAssertionSummary(result);
        if (summary.isBlank()) {
            summary = "HTTP " + result.getActualStatusCode();
        }
        return Optional.of(truncate(summary));
    }

    /** 汇总失败断言：断言名 + 实际值，多个用分号连接。 */
    private String failedAssertionSummary(TestResult result) {
        if (result.getAssertionDetails() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AssertionDetail detail : result.getAssertionDetails()) {
            if (detail.isPassed()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(detail.getAssertionName());
            if (detail.getActual() != null && !detail.getActual().isBlank()) {
                sb.append(" 实际=").append(detail.getActual());
            }
        }
        return sb.toString();
    }

    private boolean isConnectionNoise(TestResult result) {
        return (result.getErrorMessage() != null && !result.getErrorMessage().isBlank())
                || hasFailedAssertion(result, "Connection Check");
    }

    private Optional<String> failedSemanticAssertion(TestResult result) {
        if (result.getAssertionDetails() == null) {
            return Optional.empty();
        }
        for (AssertionDetail detail : result.getAssertionDetails()) {
            if (!detail.isPassed() && SEMANTIC_ASSERTIONS.contains(detail.getAssertionName())) {
                String summary = detail.getMessage();
                return Optional.of(truncate(summary == null || summary.isBlank()
                        ? detail.getAssertionName() : summary));
            }
        }
        return Optional.empty();
    }

    private boolean hasFailedAssertion(TestResult result, String assertionName) {
        return result.getAssertionDetails() != null
                && result.getAssertionDetails().stream()
                        .anyMatch(a -> assertionName.equals(a.getAssertionName()) && !a.isPassed());
    }

    private String truncate(String text) {
        return text.length() <= MAX_SUMMARY_LENGTH
                ? text : text.substring(0, MAX_SUMMARY_LENGTH) + "...";
    }
}
