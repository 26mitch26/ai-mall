package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiDefinition {
    private String path;
    private String method;
    private String summary;
    private List<Parameter> parameters;
    private Map<String, Response> responses;

    /**
     * 文档声明响应状态码集合——契约 oracle 的数据源。
     *
     * <p>测试用例的期望值不靠硬编码猜测，而以此集合为准：
     * 声明内的状态码可作精确断言；声明外的错误码只能弱断言（服务端以 4xx 拒绝）；
     * 正向用例在文档未声明任何成功码时无从验证，直接作废。
     *
     * <p>仅取纯三位数字 key；"default"、"2XX" 等通配 key 无法参与精确匹配，不纳入。
     */
    public Set<Integer> declaredStatusCodes() {
        if (responses == null || responses.isEmpty()) {
            return Set.of();
        }
        return responses.keySet().stream()
                .map(ApiDefinition::parseStatusCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 文档声明的成功响应码（取最小的 2xx；200 优先于 201/204 等仅按数值序）。
     * 文档未声明任何成功响应时返回 empty——此时正向调用没有可依据的期望值。
     */
    public Optional<Integer> declaredSuccessCode() {
        return declaredStatusCodes().stream()
                .filter(code -> code >= 200 && code < 300)
                .min(Integer::compareTo);
    }

    private static Integer parseStatusCode(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (!trimmed.matches("\\d{3}")) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
