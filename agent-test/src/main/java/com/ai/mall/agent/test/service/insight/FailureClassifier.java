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
