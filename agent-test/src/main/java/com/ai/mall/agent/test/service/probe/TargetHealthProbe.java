package com.ai.mall.agent.test.service.probe;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.EnvironmentCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 开跑前的环境可达性探针。
 *
 * <p>不替代任何断言，只回答一个问题：这轮红灯有没有可能是环境没起来？
 * 探针使用独立的短超时客户端，避免拖累主执行器；任何异常都收敛为
 * reachable=false 加原因，绝不因为探针失败而中断整轮测试。
 */
@Slf4j
@Service
public class TargetHealthProbe {

    private final AgentTestConfig config;
    private final ObjectMapper objectMapper;
    private final RestTemplate probeClient;

    public TargetHealthProbe(AgentTestConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getProbe().getTimeoutMs());
        factory.setReadTimeout(config.getProbe().getTimeoutMs());
        this.probeClient = new RestTemplate(factory);
    }

    /**
     * 探测目标可达性。任何失败都收敛为 unreachable，不抛异常。
     *
     * @param module  模块名，写入结论便于定位
     * @param baseUrl 被测 base URL
     */
    public EnvironmentCheck probe(String module, String baseUrl) {
        AgentTestConfig.ProbeConfig probe = config.getProbe();
        if (!probe.isEnabled()) {
            return EnvironmentCheck.builder().module(module).target(baseUrl)
                    .skipped(true).detail("环境探针已关闭").build();
        }
        String url = trimTrailingSlash(baseUrl) + probe.getPath();
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = probeClient.getForEntity(url, String.class);
            long cost = System.currentTimeMillis() - start;
            String detail = extractHealth(response.getBody());
            int status = response.getStatusCode().value();
            boolean healthy = status >= 200 && status < 300
                    && (detail.isBlank() || probe.getHealthyStatuses().contains(detail));
            return EnvironmentCheck.builder().module(module).target(baseUrl).reachable(healthy)
                    .statusCode(status).latencyMs(cost)
                    .detail(healthy ? detail : "状态异常：" + detail).build();
        } catch (Exception e) {
            log.warn("Environment probe failed for {} ({}): {}", module, url, e.getMessage());
            return EnvironmentCheck.builder().module(module).target(baseUrl).reachable(false)
                    .latencyMs(System.currentTimeMillis() - start)
                    .detail("不可达：" + e.getClass().getSimpleName()).build();
        }
    }

    private String trimTrailingSlash(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 提取 Actuator 的 status 字段；非 Actuator 响应返回空串。 */
    private String extractHealth(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            var status = objectMapper.readTree(body).path("status");
            return status.isTextual() ? status.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
