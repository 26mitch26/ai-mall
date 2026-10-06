package com.ai.mall.agent.test.service.insight;

import com.ai.mall.agent.test.model.TestCase;
import com.ai.mall.agent.test.model.TestResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureClassifierTest {
    private final FailureClassifier classifier = new FailureClassifier();

    private TestCase caseOf() {
        return TestCase.builder().id("t").name("c").apiPath("/api/orders").method("GET")
                .expectedStatusCode(200).build();
    }

    private TestResult semanticFailure(String name) {
        return TestResult.builder().testCaseId("t").testCaseName("c")
                .passed(false).actualStatusCode(200)
                .assertionDetails(java.util.List.of(
                        com.ai.mall.agent.test.model.AssertionDetail.builder()
                                .assertionName(name).passed(false)
                                .expected("x").actual("y").message(name + " failed").build()))
                .build();
    }

    @Test
    void businessCodeSwallowErrorShouldBeClassified() {
        assertTrue(classifier.classify(caseOf(),
                semanticFailure("Business Code Check (CommonResult)")).isPresent(),
                "吞错是高置信疑似缺陷，应沉淀");
    }

    @Test
    void schemaViolationShouldBeClassified() {
        assertTrue(classifier.classify(caseOf(),
                semanticFailure("OpenAPI Schema Check")).isPresent());
    }

    @Test
    void connectionFailureShouldBeNoise() {
        TestResult r = TestResult.builder().testCaseId("t").testCaseName("c")
                .passed(false).actualStatusCode(0)
                .errorMessage("Connection refused: connect")
                .build();
        assertFalse(classifier.classify(caseOf(), r).isPresent(), "连接失败是环境噪音");
    }

    @Test
    void serverErrorShouldBeClassified() {
        TestResult r = TestResult.builder().testCaseId("t").testCaseName("c")
                .passed(false).actualStatusCode(500)
                .assertionDetails(java.util.List.of())
                .build();
        assertTrue(classifier.classify(caseOf(), r).isPresent());
    }

    @Test
    void pureStatusMismatchShouldNotBeClassified() {
        TestResult r = TestResult.builder().testCaseId("t").testCaseName("c")
                .passed(false).actualStatusCode(404)
                .assertionDetails(java.util.List.of(
                        com.ai.mall.agent.test.model.AssertionDetail.builder()
                                .assertionName("Status Code Check (contract)")
                                .passed(false).expected("200").actual("404").message("m").build()))
                .build();
        assertFalse(classifier.classify(caseOf(), r).isPresent(),
                "纯状态码偏差信号不足，不应沉淀");
    }

    @Test
    void passedResultShouldBeIgnored() {
        TestResult r = TestResult.builder().testCaseId("t").testCaseName("c").passed(true).build();
        assertFalse(classifier.classify(caseOf(), r).isPresent());
    }

    // ==================== 会话/评测类结果 ====================

    private TestResult conversationalFailure(String assertionName, String actual) {
        return TestResult.builder().testCaseId("s1").testCaseName("会话用例")
                .passed(false).actualStatusCode(200)
                .assertionDetails(java.util.List.of(
                        com.ai.mall.agent.test.model.AssertionDetail.builder()
                                .assertionName(assertionName).passed(false)
                                .expected("ok").actual(actual).message("未通过").build()))
                .build();
    }

    @Test
    void conversationalAssertionFailureIsAlwaysHighValueSignal() {
        var summary = classifier.classifyConversational(
                conversationalFailure("拒答正确性 (expectedRefusal)", "抱歉，我无法回答"));
        assertTrue(summary.isPresent(),
                "会话断言名是动态拼的，不可能在契约层白名单里；除噪音外都应沉淀");
        assertTrue(summary.get().contains("拒答正确性"));
        assertTrue(summary.get().contains("抱歉，我无法回答"));
    }

    @Test
    void conversationalConnectionNoiseIsStillExcluded() {
        TestResult r = TestResult.builder().testCaseId("s1").testCaseName("会话用例")
                .passed(false).actualStatusCode(0)
                .errorMessage("Connection refused")
                .build();
        assertFalse(classifier.classifyConversational(r).isPresent(),
                "环境噪音不应被记成 Agent 质量缺陷");
    }

    @Test
    void conversationalPassedResultIsIgnored() {
        TestResult r = TestResult.builder().testCaseId("s1").passed(true).build();
        assertFalse(classifier.classifyConversational(r).isPresent());
    }

    @Test
    void conversationalFailureWithoutAssertionFallsBackToStatusCode() {
        TestResult r = TestResult.builder().testCaseId("s1").passed(false)
                .actualStatusCode(503).build();
        assertTrue(classifier.classifyConversational(r).orElse("").contains("503"));
    }
}
