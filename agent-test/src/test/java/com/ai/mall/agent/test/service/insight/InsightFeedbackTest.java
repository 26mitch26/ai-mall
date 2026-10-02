package com.ai.mall.agent.test.service.insight;

import com.ai.mall.agent.test.model.AssertionDetail;
import com.ai.mall.agent.test.model.KnownDefect;
import com.ai.mall.agent.test.model.TestCase;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败回流闭环端到端测试：执行失败 → 分类 → 沉淀 → 挂载报告。
 * 验证"第一轮发现新缺陷、第二轮标记为历史复现"的语义。
 */
class InsightFeedbackTest {

    private final FailureClassifier classifier = new FailureClassifier();
    private final ObjectMapper mapper = new ObjectMapper();
    private Path tempFile;

    @AfterEach
    void cleanup() throws IOException {
        if (tempFile != null && Files.exists(tempFile)) {
            Files.deleteIfExists(tempFile);
        }
    }

    private TestCase caseOf() {
        return TestCase.builder().id("t").name("c").apiPath("/api/orders").method("GET")
                .expectedStatusCode(200).build();
    }

    /** 构造一次"HTTP 200 吞错"失败：状态码断言通过但业务码断言失败 */
    private TestResult swallowedErrorResult() {
        return TestResult.builder().testCaseId("t").testCaseName("c")
                .passed(false).actualStatusCode(200)
                .assertionDetails(List.of(
                        AssertionDetail.builder()
                                .assertionName("Business Code Check (CommonResult)")
                                .passed(false).expected("code=200").actual("code=500")
                                .message("HTTP 200 成功但业务码为 500，疑似吞错").build(),
                        AssertionDetail.builder()
                                .assertionName("Status Code Check (contract)")
                                .passed(true).expected("200").actual("200").message("ok").build()))
                .build();
    }

    @Test
    void firstRunRecordsNewDefectThenSecondRunMarksRecurrence() throws IOException {
        tempFile = Files.createTempFile("insights", ".json");
        TestInsightStore store = new TestInsightStore(mapper, tempFile.toString());

        // 第一轮：发现缺陷并沉淀
        Optional<String> signal = classifier.classify(caseOf(), swallowedErrorResult());
        assertTrue(signal.isPresent(), "吞错失败应产生可沉淀信号");
        store.record("GET", "/api/orders", signal.get());
        store.markRoundCompleted();
        assertEquals(1, store.findByApi("GET", "/api/orders").getOccurrences());
        assertTrue(!store.findByApi("GET", "/api/orders").isHitThisRun());

        // 第二轮：同一接口再次命中，报告应标记为历史复现
        store.record("GET", "/api/orders", signal.get());
        TestReport report = TestReport.builder().id("r-1").moduleName("order")
                .results(List.of()).build();
        report.setKnownDefects(store.snapshot());

        assertEquals(1, report.getKnownDefects().size());
        KnownDefect defect = report.getKnownDefects().get(0);
        assertEquals("GET /api/orders", defect.getApiKey());
        assertEquals(2, defect.getOccurrences(), "第二轮命中后累计为 2 次");
        assertTrue(defect.isHitThisRun(), "本轮复现应打标");
        assertTrue(defect.getSummary().contains("吞错"));
    }
}
