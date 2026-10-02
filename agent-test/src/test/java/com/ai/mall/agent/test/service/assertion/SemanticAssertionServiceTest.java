package com.ai.mall.agent.test.service.assertion;

import com.ai.mall.agent.test.model.AssertionDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义断言服务测试：业务码吞错检测、分页不变式、Schema 校验的启用条件与判定。
 */
class SemanticAssertionServiceTest {

    private final SemanticAssertionService service =
            new SemanticAssertionService(new ObjectMapper(), new OpenApiSchemaValidator());

    private AssertionDetail byName(List<AssertionDetail> details, String name) {
        return details.stream()
                .filter(d -> d.getAssertionName().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Test
    void http200WithBusinessCode200ShouldPass() {
        List<AssertionDetail> result = service.evaluate(
                "{\"code\":200,\"message\":\"ok\",\"data\":null}", 200, null);
        assertTrue(byName(result, "Business Code Check (CommonResult)").isPassed());
    }

    @Test
    void http200WithBusinessCode500ShouldFailAsSwallowedError() {
        List<AssertionDetail> result = service.evaluate(
                "{\"code\":500,\"message\":\"internal error\",\"data\":null}", 200, null);
        assertFalse(byName(result, "Business Code Check (CommonResult)").isPassed(),
                "HTTP 200 + 业务码 500 = 吞错，必须判失败");
    }

    @Test
    void nonCommonResultBodyShouldSkipBusinessCodeCheck() {
        List<AssertionDetail> result = service.evaluate("[1,2,3]", 200, null);
        assertTrue(byName(result, "Business Code Check (CommonResult)") == null,
                "非包装结构不应产出业务码断言");
    }

    @Test
    void http400WithAnyBusinessCodeShouldNotAssert() {
        List<AssertionDetail> result = service.evaluate(
                "{\"code\":400,\"message\":\"bad\",\"data\":null}", 400, null);
        assertTrue(byName(result, "Business Code Check (CommonResult)").isPassed(),
                "HTTP 失败时业务码值域由业务自定义，不约束");
    }

    @Test
    void paginationInvariantShouldPassWhenTotalCoversList() {
        List<AssertionDetail> result = service.evaluate(
                "{\"code\":200,\"data\":{\"total\":100,\"list\":[1,2,3]}}", 200, null);
        assertTrue(byName(result, "Pagination Invariant Check (total >= list.size)").isPassed());
    }

    @Test
    void paginationInvariantShouldFailWhenTotalShrinks() {
        List<AssertionDetail> result = service.evaluate(
                "{\"code\":200,\"data\":{\"total\":2,\"list\":[1,2,3,4,5]}}", 200, null);
        assertFalse(byName(result, "Pagination Invariant Check (total >= list.size)").isPassed(),
                "total < 当前页条数，分页语义被破坏");
    }

    @Test
    void nonPaginationBodyShouldSkipInvariant() {
        List<AssertionDetail> result = service.evaluate(
                "{\"code\":200,\"data\":{\"id\":1}}", 200, null);
        assertTrue(byName(result, "Pagination Invariant Check (total >= list.size)") == null,
                "非分页响应不应产出分页断言");
    }

    @Test
    void schemaViolationShouldFailWhenDeclaredSchemaApplicable() {
        String schema = """
                {"type":"object","required":["code"],
                 "properties":{"code":{"type":"integer"}}}""";
        List<AssertionDetail> result = service.evaluate(
                "{\"code\":\"not_a_number\"}", 200, schema);
        assertFalse(byName(result, "OpenAPI Schema Check").isPassed(),
                "实际响应与文档声明不符，Schema 断言应失败");
    }

    @Test
    void blankSchemaShouldBeSkipped() {
        List<AssertionDetail> result = service.evaluate(
                "{\"code\":200}", 200, "  ");
        assertTrue(byName(result, "OpenAPI Schema Check") == null);
    }
}
