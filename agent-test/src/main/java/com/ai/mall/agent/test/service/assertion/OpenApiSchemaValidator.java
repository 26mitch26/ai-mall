package com.ai.mall.agent.test.service.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 轻量 OpenAPI Schema 校验器：用 API 文档声明校验实际响应体的结构一致性。
 *
 * <h3>设计取舍</h3>
 * <ul>
 *   <li><b>为什么不引入 networknt 等 JSON Schema 库</b>：OpenAPI 的 Schema 是
 *       JSON Schema 方言，且响应声明普遍使用 {@code $ref: '#/components/schemas/...'}
 *       引用——脱离完整文档上下文时外部库无法解析引用，反而导致大量误报。
 *       自实现可以精确控制支持范围与降级语义。</li>
 *   <li><b>支持范围</b>：type（object/array/string/integer/number/boolean）、
 *       required + properties、items、enum。这些覆盖常见 CRUD 接口响应结构的
 *       绝大多数契约点；format（date-time 等）与组合关键字（allOf/oneOf）不支持，
 *       遇到即跳过该节点，宁漏报不误报。</li>
 *   <li><b>降级语义</b>：schema 缺失、无法解析或含未支持的顶层结构时返回空违规列表
 *       （视为"无法校验"而非"通过"），由调用方决定是否纳入断言。</li>
 * </ul>
 */
@Component
public class OpenApiSchemaValidator {

    /** 校验结果：空列表 = 无违规（通过或无法校验），否则为违规描述 */
    public List<String> validate(JsonNode value, JsonNode schema) {
        List<String> violations = new ArrayList<>();
        if (schema == null || schema.isNull() || schema.isEmpty()) {
            return violations;
        }
        if (schema.has("$ref")) {
            // 组件引用脱离文档上下文无法解析，跳过而非误判
            return violations;
        }
        validateNode("$", value, schema, violations);
        return violations;
    }

    private void validateNode(String path, JsonNode value, JsonNode schema, List<String> violations) {
        if (schema == null || !schema.isObject()) {
            return;
        }

        String type = schema.hasNonNull("type") ? schema.get("type").asText() : null;
        if (type == null && schema.has("properties")) {
            type = "object";
        }
        if (type == null) {
            return;
        }

        switch (type) {
            case "object" -> validateObject(path, value, schema, violations);
            case "array" -> validateArray(path, value, schema, violations);
            case "string" -> validateString(path, value, schema, violations);
            case "integer", "number" -> validateNumber(path, value, type, violations);
            case "boolean" -> validateBoolean(path, value, violations);
            default -> {
                // null / 未知类型：跳过
            }
        }
    }

    private void validateObject(String path, JsonNode value, JsonNode schema, List<String> violations) {
        if (!value.isObject()) {
            violations.add(path + " 应为 object，实际为 " + nodeTypeOf(value));
            return;
        }

        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            for (JsonNode required : requiredNode) {
                String name = required.asText();
                if (!value.has(name) || value.get(name).isNull()) {
                    violations.add(path + "." + name + " 缺失必填字段");
                }
            }
        }

        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                if (value.has(name) && !value.get(name).isNull()) {
                    validateNode(path + "." + name, value.get(name), field.getValue(), violations);
                }
            }
        }
    }

    private void validateArray(String path, JsonNode value, JsonNode schema, List<String> violations) {
        if (!value.isArray()) {
            violations.add(path + " 应为 array，实际为 " + nodeTypeOf(value));
            return;
        }
        JsonNode items = schema.get("items");
        if (items == null) {
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            validateNode(path + "[" + i + "]", value.get(i), items, violations);
        }
    }

    private void validateString(String path, JsonNode value, JsonNode schema, List<String> violations) {
        if (!value.isTextual()) {
            violations.add(path + " 应为 string，实际为 " + nodeTypeOf(value));
            return;
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray() && !enumNode.isEmpty()) {
            boolean matched = false;
            for (JsonNode candidate : enumNode) {
                if (candidate.asText().equals(value.asText())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                violations.add(path + " 取值 " + value.asText()
                        + " 不在契约枚举 " + enumNode.toString() + " 内");
            }
        }
    }

    private void validateNumber(String path, JsonNode value, String declaredType, List<String> violations) {
        if (!value.isNumber()) {
            violations.add(path + " 应为 " + declaredType + "，实际为 " + nodeTypeOf(value));
            return;
        }
        // integer 比 number 更严格：小数部分必须为零
        if ("integer".equals(declaredType) && !value.isIntegralNumber()) {
            violations.add(path + " 应为 integer，实际为小数 " + value.asText());
        }
    }

    private void validateBoolean(String path, JsonNode value, List<String> violations) {
        if (!value.isBoolean()) {
            violations.add(path + " 应为 boolean，实际为 " + nodeTypeOf(value));
        }
    }

    private String nodeTypeOf(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        if (value.isTextual()) return "string";
        if (value.isIntegralNumber()) return "integer";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        return "unknown";
    }
}
