package com.ai.mall.agent.test.service.generator;

import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.Parameter;
import com.ai.mall.agent.test.model.Response;
import com.ai.mall.agent.test.model.TestCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则生成器契约自洽性测试：生成器产出的每条用例，其期望值
 * 都必须能追溯到 OpenAPI 声明（精确断言）或降级为 4xx 弱断言。
 *
 * <p>这是"期望值不靠猜"的回归防线——任何人改动生成器模板，
 * 只要产出了无契约背书的期望，本测试即失败。
 */
class TestCaseGeneratorContractTest {

    private final TestCaseGenerator generator = new TestCaseGenerator(new TestCaseContractGuard());

    private ApiDefinition apiWithResponses(String... statusCodes) {
        Map<String, Response> responses = new LinkedHashMap<>();
        for (String code : statusCodes) {
            responses.put(code, Response.builder().description("declared").build());
        }
        return ApiDefinition.builder()
                .path("/api/products/{id}")
                .method("GET")
                .summary("查询商品详情")
                .parameters(List.of(
                        Parameter.builder().name("id").in("path").type("string").required(true).build(),
                        Parameter.builder().name("page").in("query").type("integer").required(false).build()))
                .responses(responses)
                .build();
    }

    @Test
    @DisplayName("契约完整（200/400/404）时：正向精确锚定 200，负向全部精确断言")
    void generatedCasesShouldAlignWithFullContract() {
        List<TestCase> cases = generator.generateTestCases(apiWithResponses("200", "400", "404"));

        assertFalse(cases.isEmpty());
        for (TestCase tc : cases) {
            if (tc.getExpectedStatusCode() < 400) {
                assertEquals(200, tc.getExpectedStatusCode(),
                        "正向用例期望码应为文档声明的 200");
                assertTrue(tc.isStrictExpectation(), "文档声明了成功码，正向用例应精确断言");
            } else {
                assertTrue(tc.isStrictExpectation(),
                        "负向期望码 " + tc.getExpectedStatusCode() + " 在契约声明内，应精确断言");
            }
        }
    }

    @Test
    @DisplayName("契约只声明 200 时：负向用例全部降级 4xx 弱断言")
    void undeclaredErrorCodesShouldDegradeToWeakAssertion() {
        List<TestCase> cases = generator.generateTestCases(apiWithResponses("200"));

        boolean hasNegativeCase = cases.stream().anyMatch(tc -> tc.getExpectedStatusCode() >= 400);
        assertTrue(hasNegativeCase, "缺少必填参数等负向用例应保留（弱断言），而非全部丢弃");

        for (TestCase tc : cases) {
            if (tc.getExpectedStatusCode() >= 400) {
                assertFalse(tc.isStrictExpectation(),
                        "未声明的错误码 " + tc.getExpectedStatusCode() + " 应为弱断言");
            } else {
                assertTrue(tc.isStrictExpectation());
            }
        }
    }

    @Test
    @DisplayName("文档未声明成功响应时：正向用例作废，只保留可验证的负向用例")
    void successCaseShouldBeDroppedWithoutDeclaredSuccess() {
        List<TestCase> cases = generator.generateTestCases(apiWithResponses("400"));

        assertTrue(cases.stream().noneMatch(tc -> tc.getExpectedStatusCode() < 400),
                "无成功码声明时正向用例无 oracle，必须作废");
        assertFalse(cases.isEmpty(), "负向用例（弱断言）仍应保留");
    }

    @Test
    @DisplayName("正向用例期望码应跟随契约：声明 201 的创建语义不误标为 200")
    void successExpectationShouldFollowContract() {
        ApiDefinition postApi = ApiDefinition.builder()
                .path("/api/orders")
                .method("POST")
                .parameters(List.of())
                .responses(Map.of(
                        "201", Response.builder().description("created").build(),
                        "400", Response.builder().description("bad request").build()))
                .build();

        List<TestCase> cases = generator.generateTestCases(postApi);

        List<TestCase> successCases = cases.stream()
                .filter(tc -> tc.getExpectedStatusCode() < 400)
                .toList();
        assertFalse(successCases.isEmpty(), "POST 契约声明了 201，应有正向用例");
        for (TestCase tc : successCases) {
            assertEquals(201, tc.getExpectedStatusCode(),
                    "旧版硬编码 200 会把创建语义测错，契约对齐后应为 201");
        }
    }
}
