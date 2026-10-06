package com.ai.mall.agent.test.service.support;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * 演示会员登录（换取 JWT）。
 *
 * <p>抽成独立服务是因为会话场景套件与质量评测套件都需要它，而登录本身踩过一个坑：
 * 用 URL 预编码参数提交表单时，{@code @} 会被二次编码（%40 → %2540），
 * 导致明明凭据正确却报"用户名或密码错误"。这里统一用表单体提交，不再手写 URL 编码。
 */
@Slf4j
@Service
public class MemberLoginService {

    private final ObjectMapper objectMapper;
    private final AgentTestConfig config;
    private final RestTemplate http;

    public MemberLoginService(ObjectMapper objectMapper, AgentTestConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getScenario().getConnectTimeoutMs());
        factory.setReadTimeout(config.getScenario().getChatTimeoutMs());
        this.http = new RestTemplate(factory);
    }

    /**
     * 登录并返回 JWT；失败返回 null（不抛出，由调用方逐用例标注失败原因）。
     */
    public String login() {
        AgentTestConfig.ScenarioConfig scenario = config.getScenario();
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("username", scenario.getLoginUsername());
            form.add("password", scenario.getLoginPassword());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String body = http.postForObject(scenario.getGatewayUrl() + "/sso/login",
                    new HttpEntity<>(form, headers), String.class);
            JsonNode root = objectMapper.readTree(body);
            String token = root.path("data").path("token").asText("");
            if (token.isBlank()) {
                log.warn("Member login returned no token");
                return null;
            }
            log.info("Logged in as member {}", scenario.getLoginUsername());
            return token;
        } catch (Exception e) {
            log.warn("Member login failed: {}", e.getMessage());
            return null;
        }
    }
}
