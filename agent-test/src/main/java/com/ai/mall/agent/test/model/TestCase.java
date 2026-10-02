package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCase {
    private String id;
    private String name;
    private String apiPath;
    private String method;
    private Map<String, Object> requestParams;
    private int expectedStatusCode;
    private String expectedResponse;
    private String description;

    /**
     * 期望断言强度。
     *
     * <p>true = 精确断言：期望码有 OpenAPI 契约背书（文档 responses 中声明过），
     * 实际状态码必须与之相等。
     *
     * <p>false = 弱断言（服务端正确拒绝）：文档未声明具体错误码时，
     * "返回哪个 4xx"（400/422...）属于框架实现细节而非契约承诺，
     * 此时只要求服务端以 4xx 拒绝非法输入，避免把实现细节当契约导致误报。
     *
     * <p>默认 true；生成器对齐契约时会为无契约背书的负向用例置为 false。
     */
    @Builder.Default
    private boolean strictExpectation = true;

    /**
     * 文档为期望状态码声明的响应 schema（JSON 字符串，null = 未声明）。
     * 由契约守卫在对齐时回填，供执行器做 OpenAPI Schema 结构断言。
     */
    private String responseSchema;

    /**
     * 按指定断言强度重设期望状态码，返回自身（生成器链式对齐用）。
     */
    public TestCase withExpectation(int statusCode, boolean strict) {
        setExpectedStatusCode(statusCode);
        setStrictExpectation(strict);
        return this;
    }
}
