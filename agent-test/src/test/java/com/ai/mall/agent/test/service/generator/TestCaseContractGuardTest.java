package com.ai.mall.agent.test.service.generator;

import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.Parameter;
import com.ai.mall.agent.test.model.Response;
import com.ai.mall.agent.test.model.TestCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约守卫单元测试：验证期望值锚定 OpenAPI 声明、AI 幻觉被拦截。
 *
 * <p>守卫解决的是测试生成的 oracle problem——用例对不对取决于期望值是否可信。
 * 本测试锁定三条不变式：
 * <ol>
 *   <li>正向用例的期望码必须来自文档声明的成功码，无声明则用例作废；</li>
 *   <li>负向用例：声明内精确断言，声明外降级 4xx 弱断言而非丢弃（"被拒绝"仍可验证）；</li>
 *   <li>AI 产出的路径/方法/参数/状态码必须与文档一致，幻觉直接拦截。</li>
 * </ol>
 */
class TestCaseContractGuardTest {

    private final TestCaseContractGuard guard = new TestCaseContractGuard();

    /** 被测 API：GET /api/products/{id}，声明 200/404，参数 id(path) 与 page(query) */
    private ApiDefinition api() {
        return ApiDefinition.builder()
                .path("/api/products/{id}")
                .method("GET")
                .summary("查询商品详情")
                .parameters(List.of(
                        Parameter.builder().name("id").in("path").type("string").required(true).build(),
                        Parameter.builder().name("page").in("query").type("integer").required(false).build()))
                .responses(Map.of(
                        "200", Response.builder().description("ok").build(),
                        "404", Response.builder().description("not found").build()))
                .build();
    }

    private TestCase aiCase(String path, String method, Map<String, Object> params, int expected) {
        return TestCase.builder()
                .id("ai-1")
                .name("AI case")
                .apiPath(path)
                .method(method)
                .requestParams(params)
                .expectedStatusCode(expected)
                .expectedResponse("AI generated assertion")
                .description("ai")
                .build();
    }

    // ======================== 正向对齐 ========================

    @Test
    @DisplayName("正向用例期望码应锚定为文档声明的成功码（200→文档声明的值）")
    void alignSuccessShouldAnchorToDeclaredSuccessCode() {
        ApiDefinition api = ApiDefinition.builder()
                .path("/api/orders")
                .method("POST")
                .responses(Map.of("201", Response.builder().description("created").build()))
                .build();

        TestCase aligned = guard.alignSuccess(aiCase("/api/orders", "POST", null, 200), api);

        assertEquals(201, aligned.getExpectedStatusCode(),
                "文档声明 201 时，期望码应对齐为 201 而非硬编码的 200");
        assertTrue(aligned.isStrictExpectation(), "有契约背书的期望应为精确断言");
    }

    @Test
    @DisplayName("文档未声明任何成功响应时，正向用例应作废（无 oracle 不可验证）")
    void successCaseShouldBeVoidWithoutDeclaredSuccess() {
        ApiDefinition api = ApiDefinition.builder()
                .path("/api/products/{id}")
                .method("GET")
                .responses(Map.of("404", Response.builder().description("not found").build()))
                .build();

        assertNull(guard.alignSuccess(aiCase("/api/products/{id}", "GET", null, 200), api),
                "文档连 2xx 都没声明，正向调用的期望无从依据，应作废");
    }

    // ======================== 负向对齐 ========================

    @Test
    @DisplayName("文档声明了错误码 → 精确断言")
    void declaredErrorShouldBeStrict() {
        TestCase aligned = guard.alignRejection(aiCase("/api/products/{id}", "GET", null, 404), api());

        assertTrue(aligned.isStrictExpectation(), "404 在文档声明内，应精确断言");
        assertEquals(404, aligned.getExpectedStatusCode());
    }

    @Test
    @DisplayName("文档未声明的错误码 → 降级弱断言（仅要求 4xx 拒绝）而非丢弃")
    void undeclaredErrorShouldDegradeToWeakAssertion() {
        TestCase aligned = guard.alignRejection(aiCase("/api/products/{id}", "GET", null, 400), api());

        assertFalse(aligned.isStrictExpectation(),
                "400 未在文档声明，'返回哪个 4xx'属实现细节，应降级弱断言");
        assertEquals(400, aligned.getExpectedStatusCode(), "期望码保留用于报告展示");
    }

    // ======================== AI 幻觉拦截 ========================

    @Test
    @DisplayName("幻觉路径应被拦截（LLM 常编造相邻路径）")
    void hallucinatedPathShouldBeDropped() {
        Optional<TestCase> result = guard.guardAiCase(
                aiCase("/api/products/{id}/reviews", "GET", null, 200), api());

        assertFalse(result.isPresent(), "路径不在文档中，必须丢弃");
    }

    @Test
    @DisplayName("幻觉方法应被拦截")
    void hallucinatedMethodShouldBeDropped() {
        Optional<TestCase> result = guard.guardAiCase(
                aiCase("/api/products/{id}", "DELETE", null, 200), api());

        assertFalse(result.isPresent(), "文档声明的是 GET，DELETE 用例必须丢弃");
    }

    @Test
    @DisplayName("幻觉参数应被拦截（参数名不属于该 API 声明参数集）")
    void hallucinatedParamShouldBeDropped() {
        Optional<TestCase> result = guard.guardAiCase(
                aiCase("/api/products/{id}", "GET",
                        Map.of("id", "1", "admin_token", "x"), 200), api());

        assertFalse(result.isPresent(), "admin_token 不是文档声明的参数，必须丢弃");
    }

    @Test
    @DisplayName("未声明的 2xx 应被丢弃（正向期望必须来自契约）")
    void undeclared2xxShouldBeDropped() {
        // 文档只声明了 200/404，AI 猜测的 204 无背书
        Optional<TestCase> result = guard.guardAiCase(
                aiCase("/api/products/{id}", "GET", Map.of("id", "1"), 204), api());

        assertFalse(result.isPresent(), "204 未声明且非错误码，正向期望必须来自契约，丢弃");
    }

    @Test
    @DisplayName("服务端 5xx 猜测应被丢弃（故障不是可断言行为）")
    void guessed5xxShouldBeDropped() {
        Optional<TestCase> result = guard.guardAiCase(
                aiCase("/api/products/{id}", "GET", Map.of("id", "1"), 500), api());

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("未声明的 4xx 应降级弱断言保留，而非直接丢弃")
    void undeclared4xxShouldBeDowngradedButKept() {
        Optional<TestCase> result = guard.guardAiCase(
                aiCase("/api/products/{id}", "GET", Map.of("id", "1"), 422), api());

        assertTrue(result.isPresent(), "422 是合理的拒绝语义，降级弱断言后保留");
        assertFalse(result.get().isStrictExpectation());
    }

    @Test
    @DisplayName("合法用例应通过守卫并按契约打上精确断言标记")
    void validCaseShouldPassWithContractBackedExpectation() {
        Optional<TestCase> result = guard.guardAiCase(
                aiCase("/api/products/{id}", "GET", Map.of("id", "1", "page", "2"), 404), api());

        assertTrue(result.isPresent());
        assertTrue(result.get().isStrictExpectation(), "404 已声明，精确断言");
    }
}
