package com.ai.mall.agent.test.service.assertion;

import com.ai.mall.agent.test.model.AssertionDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 响应体语义断言：补齐"HTTP 语义正确但业务语义错误"的检测盲区。
 * 典型缺陷：接口吞掉内部错误返回 HTTP 200 + {"code":500}。
 * 本项目统一响应包装 CommonResult{code,message,data}（成功码 200），
 * 业务码与 HTTP 语义的一致性因此可断言。
 *
 * 三类断言全部条件性启用，不适配的结构自动跳过：
 * 1. 业务码断言：CommonResult 包装时 HTTP 2xx 必须伴随业务成功码；
 * 2. 分页不变式：total >= list.size()，兼容顶层与 data 层包装；
 * 3. OpenAPI Schema 校验：文档声明了可离线解析的 schema 时校验结构一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticAssertionService {

    private static final long BUSINESS_SUCCESS_CODE = 200L;

    private final ObjectMapper objectMapper;
    private final OpenApiSchemaValidator schemaValidator;

    public List<AssertionDetail> evaluate(String responseBody, int actualStatusCode, String declaredSchema) {
        List<AssertionDetail> assertions = new ArrayList<>();
        if (responseBody == null || responseBody.isBlank()) {
            return assertions;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            return assertions; // 非 JSON 由执行器通用断言负责
        }
        assertBusinessCode(root, actualStatusCode).ifPresent(assertions::add);
        assertPaginationInvariant(root).ifPresent(assertions::add);
        assertions.addAll(assertSchema(root, declaredSchema));
        return assertions;
    }

    /** 业务码断言：HTTP 成功时业务码必须为成功码，拦截吞错。 */
    private Optional<AssertionDetail> assertBusinessCode(JsonNode root, int actualStatusCode) {
        JsonNode codeNode = root.get("code");
        if (codeNode == null || !codeNode.isNumber()) {
            return Optional.empty(); // 非 CommonResult 包装，不适用
        }
        long businessCode = codeNode.asLong();
        boolean httpSuccess = actualStatusCode < 400;
        boolean passed = !httpSuccess || businessCode == BUSINESS_SUCCESS_CODE;
        return Optional.of(AssertionDetail.builder()
                .assertionName("Business Code Check (CommonResult)")
                .passed(passed)
                .expected(httpSuccess ? "code=" + BUSINESS_SUCCESS_CODE : "any business code")
                .actual("code=" + businessCode)
                .message(passed
                        ? "HTTP " + actualStatusCode + " 与业务码 " + businessCode + " 语义一致"
                        : "HTTP " + actualStatusCode + " 成功但业务码为 " + businessCode + "，疑似吞错")
                .build());
    }

    /** 分页不变式：total >= list.size()，兼容顶层与 data 层包装。 */
    private Optional<AssertionDetail> assertPaginationInvariant(JsonNode root) {
        JsonNode page = paginationNode(root);
        if (page == null) {
            return Optional.empty();
        }
        long total = page.get("total").asLong();
        int size = page.get("list").size();
        boolean passed = total >= size;
        return Optional.of(AssertionDetail.builder()
                .assertionName("Pagination Invariant Check (total >= list.size)")
                .passed(passed)
                .expected("total(" + total + ") >= list.size(" + size + ")")
                .actual("total=" + total + ", list.size=" + size)
                .message(passed ? "分页不变式成立"
                        : "分页不变式被破坏：total=" + total + " 小于当前页条数 " + size)
                .build());
    }

    /** 定位分页节点：顶层 {total, list} 或 CommonResult 的 data 层。 */
    private JsonNode paginationNode(JsonNode root) {
        if (hasPaginationShape(root)) {
            return root;
        }
        JsonNode data = root.get("data");
        return data != null && hasPaginationShape(data) ? data : null;
    }

    private boolean hasPaginationShape(JsonNode node) {
        return node != null && node.isObject()
                && node.has("total") && node.get("total").isNumber()
                && node.has("list") && node.get("list").isArray();
    }

    /** Schema 校验：文档为该状态码声明了可解析 schema 时校验响应结构。 */
    private List<AssertionDetail> assertSchema(JsonNode root, String declaredSchema) {
        if (declaredSchema == null || declaredSchema.isBlank()) {
            return List.of();
        }
        JsonNode schemaNode;
        try {
            schemaNode = objectMapper.readTree(declaredSchema);
        } catch (JsonProcessingException e) {
            log.debug("Declared schema not valid JSON, skip: {}", e.getMessage());
            return List.of();
        }
        if (schemaNode == null || schemaNode.isEmpty()) {
            return List.of();
        }
        List<String> violations = schemaValidator.validate(root, schemaNode);
        if (violations.isEmpty()) {
            return List.of();
        }
        return List.of(AssertionDetail.builder()
                .assertionName("OpenAPI Schema Check")
                .passed(false)
                .expected("response conforms to declared schema")
                .actual(String.join("; ", violations))
                .message("响应结构与 API 文档声明不一致：" + String.join("; ", violations))
                .build());
    }
}
