package com.ai.mall.agent.test.service.mcp;

import com.ai.mall.agent.test.model.AssertionDetail;
import com.ai.mall.agent.test.model.KnownDefect;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 {@link TestReport} 投影成 Agent 易消费的 JSON。
 *
 * <p>刻意不复用 HTML 报告，也不直接把领域对象丢给 {@code ObjectMapper}：
 * <ul>
 *   <li>失败原因优先——把"哪条用例、哪个断言、期望什么、实际什么"提到最前面，
 *       否则 Agent 只能自己从一堆统计数字里猜；</li>
 *   <li>体积可控——{@code actualResponse} 截断、用例条数限流，避免单次 tool 结果
 *       撑爆 Agent 上下文；</li>
 *   <li>保留 {@code knownDefects}——让"第 N 次复现"的历史信息随报告一起回传，
 *       支撑 Agent 判断"这是老问题还是新回归"。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TestReportPresenter {

    private static final int MAX_RESPONSE_CHARS = 2000;
    private static final int MAX_ASSERTION_MESSAGE_CHARS = 500;

    private final ObjectMapper objectMapper;

    /** 报告列表用的紧凑视图。 */
    public ObjectNode brief(TestReport report) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", report.getId());
        node.put("moduleName", report.getModuleName());
        node.put("totalTests", report.getTotalTests());
        node.put("passedTests", report.getPassedTests());
        node.put("failedTests", report.getFailedTests());
        node.put("passRate", round(report.getPassRate()));
        node.put("averageResponseTimeMs", round(report.getAverageResponseTime()));
        node.put("assertionsFailed", report.getAssertionsFailed());
        node.put("knownDefectCount", report.getKnownDefects() == null ? 0 : report.getKnownDefects().size());
        node.put("startTime", text(report.getStartTime()));
        node.put("endTime", text(report.getEndTime()));
        return node;
    }

    /**
     * 报告详情视图。
     *
     * @param maxResults      最多返回的用例明细条数
     * @param includeResults  是否附带全部用例明细（false 时只给失败用例）
     */
    public ObjectNode detail(TestReport report, int maxResults, boolean includeResults) {
        int cap = Math.max(1, maxResults);
        ObjectNode node = brief(report);
        node.put("totalExecutionTimeMs", report.getTotalExecutionTime());
        node.put("assertionsTotal", report.getAssertionsTotal());
        node.put("assertionsPassed", report.getAssertionsPassed());

        List<TestResult> results = report.getResults() == null ? List.of() : report.getResults();
        List<TestResult> failed = results.stream().filter(result -> !result.isPassed()).toList();
        node.put("failedCount", failed.size());

        ArrayNode failedCases = node.putArray("failedCases");
        for (TestResult result : failed.stream().limit(cap).toList()) {
            failedCases.add(failedCase(result));
        }

        ArrayNode knownDefects = node.putArray("knownDefects");
        if (report.getKnownDefects() != null) {
            for (KnownDefect defect : report.getKnownDefects().stream().limit(cap).toList()) {
                ObjectNode defectNode = knownDefects.addObject();
                defectNode.put("apiKey", defect.getApiKey());
                defectNode.put("summary", defect.getSummary());
                defectNode.put("occurrences", defect.getOccurrences());
                defectNode.put("hitThisRun", defect.isHitThisRun());
                defectNode.put("lastSeen", text(defect.getLastSeen()));
            }
        }

        boolean truncated = failed.size() > cap || (includeResults && results.size() > cap);
        node.put("truncated", truncated);
        if (includeResults) {
            ArrayNode allResults = node.putArray("results");
            for (TestResult result : results.stream().limit(cap).toList()) {
                allResults.add(resultNode(result));
            }
        } else {
            node.put("hint", "includeResults=true 可获取全部用例明细");
        }
        return node;
    }

    private ObjectNode failedCase(TestResult result) {
        ObjectNode node = resultNode(result);
        ArrayNode failedAssertions = node.putArray("failedAssertions");
        if (result.getAssertionDetails() != null) {
            result.getAssertionDetails().stream().filter(detail -> !detail.isPassed()).forEach(detail -> {
                ObjectNode assertion = failedAssertions.addObject();
                assertion.put("assertionName", detail.getAssertionName());
                assertion.put("expected", truncate(detail.getExpected(), MAX_ASSERTION_MESSAGE_CHARS));
                assertion.put("actual", truncate(detail.getActual(), MAX_ASSERTION_MESSAGE_CHARS));
                assertion.put("message", truncate(detail.getMessage(), MAX_ASSERTION_MESSAGE_CHARS));
            });
        }
        return node;
    }

    private ObjectNode resultNode(TestResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("testCaseId", result.getTestCaseId());
        node.put("testCaseName", result.getTestCaseName());
        node.put("passed", result.isPassed());
        node.put("actualStatusCode", result.getActualStatusCode());
        node.put("executionTimeMs", result.getExecutionTime());
        if (result.getErrorMessage() != null) {
            node.put("errorMessage", truncate(result.getErrorMessage(), MAX_ASSERTION_MESSAGE_CHARS));
        }
        ArrayNode assertions = node.putArray("assertionDetails");
        if (result.getAssertionDetails() != null) {
            for (AssertionDetail detail : result.getAssertionDetails()) {
                ObjectNode assertion = assertions.addObject();
                assertion.put("assertionName", detail.getAssertionName());
                assertion.put("passed", detail.isPassed());
                assertion.put("expected", truncate(detail.getExpected(), MAX_ASSERTION_MESSAGE_CHARS));
                assertion.put("actual", truncate(detail.getActual(), MAX_ASSERTION_MESSAGE_CHARS));
                assertion.put("message", truncate(detail.getMessage(), MAX_ASSERTION_MESSAGE_CHARS));
            }
        }
        if (result.getActualResponse() != null && !result.getActualResponse().isBlank()) {
            node.put("actualResponse", truncate(result.getActualResponse(), MAX_RESPONSE_CHARS));
        }
        return node;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...(truncated)";
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
