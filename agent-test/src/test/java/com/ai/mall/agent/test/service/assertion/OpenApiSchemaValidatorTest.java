package com.ai.mall.agent.test.service.assertion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI Schema 校验器测试。
 * 不变式：字段缺失/类型不符/枚举越界必须报违规；
 * {@code $ref} 引用与空 schema 必须跳过而非误报（离线无法解析组件引用）。
 */
class OpenApiSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenApiSchemaValidator validator = new OpenApiSchemaValidator();

    private List<String> violations(String valueJson, String schemaJson) throws Exception {
        return validator.validate(mapper.readTree(valueJson), mapper.readTree(schemaJson));
    }

    @Test
    void conformingResponseShouldPass() throws Exception {
        String schema = """
                {"type":"object","required":["id","name"],
                 "properties":{"id":{"type":"integer"},"name":{"type":"string"},
                 "tags":{"type":"array","items":{"type":"string"}}}}""";
        String value = """
                {"id":1,"name":"iPhone","tags":["phone","apple"]}""";
        assertTrue(violations(value, schema).isEmpty());
    }

    @Test
    void missingRequiredFieldShouldViolate() throws Exception {
        String schema = """
                {"type":"object","required":["id","name"],
                 "properties":{"id":{"type":"integer"},"name":{"type":"string"}}}""";
        String value = """
                {"id":1}""";
        List<String> result = violations(value, schema);
        assertEquals(1, result.size());
        assertTrue(result.get(0).contains("name"), "应指出缺失字段，实际=" + result);
    }

    @Test
    void typeMismatchShouldViolate() throws Exception {
        String schema = """
                {"type":"object","properties":{"name":{"type":"string"}}}""";
        String value = """
                {"name":123}""";
        List<String> result = violations(value, schema);
        assertEquals(1, result.size());
        assertTrue(result.get(0).contains("应为 string"), "实际=" + result);
    }

    @Test
    void integerShouldRejectFraction() throws Exception {
        String schema = """
                {"type":"object","properties":{"price":{"type":"integer"}}}""";
        String value = """
                {"price":9.99}""";
        assertTrue(!violations(value, schema).isEmpty());
    }

    @Test
    void enumViolationShouldBeReported() throws Exception {
        String schema = """
                {"type":"object","properties":{"status":{"type":"string","enum":["PENDING","DONE"]}}}""";
        String value = """
                {"status":"UNKNOWN"}""";
        List<String> result = violations(value, schema);
        assertEquals(1, result.size());
        assertTrue(result.get(0).contains("枚举"), "实际=" + result);
    }

    @Test
    void arrayItemsShouldBeValidated() throws Exception {
        String schema = """
                {"type":"object","properties":{"ids":{"type":"array","items":{"type":"integer"}}}}""";
        String value = """
                {"ids":[1,2,"oops"]}""";
        assertEquals(1, violations(value, schema).size());
    }

    @Test
    void refShouldBeSkippedNotFalselyReported() throws Exception {
        String schema = """
                {"$ref":"#/components/schemas/Product"}""";
        String value = """
                {"whatever":1}""";
        assertTrue(violations(value, schema).isEmpty(),
                "$ref 引用离线无法解析，必须跳过而非误报");
    }

    @Test
    void nullSchemaShouldPass() throws Exception {
        assertTrue(validator.validate(mapper.readTree("{}"), null).isEmpty());
    }
}
