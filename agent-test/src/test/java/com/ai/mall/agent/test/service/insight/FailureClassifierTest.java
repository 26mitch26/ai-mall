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
}
