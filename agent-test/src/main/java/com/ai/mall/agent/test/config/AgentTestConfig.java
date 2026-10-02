package com.ai.mall.agent.test.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "test.agent")
public class AgentTestConfig {

    private String baseUrl = "http://localhost:8080";
    private int connectTimeout = 5000;
    private int readTimeout = 10000;
    private int maxConnections = 50;
    private int maxConnectionsPerRoute = 25;
    private int responseTimeThresholdMs = 5000;
    private boolean safeDemoMode = true;
    private int maxApisPerRun = 8;
    private List<String> modules;
    private Map<String, String> moduleBaseUrls;
    private AiConfig ai = new AiConfig();
    private ScenarioConfig scenario = new ScenarioConfig();

    @Data
    public static class AiConfig {
        private boolean enabled = false;
        private String model = "gpt-4o-mini";
        private double temperature = 0.3;
    }

    /**
     * 会话场景套件配置（客服 Agent 意图级用例；与契约用例互补）
     */
    @Data
    public static class ScenarioConfig {
        private boolean enabled = true;
        /** 经网关调用（需登录换取会员令牌） */
        private String gatewayUrl = "http://localhost:8080";
        private String loginUsername = "demo";
        private String loginPassword = "Demo@123";
        /** 对话携带的会员 ID（敏感工具按它会话身份绑定） */
        private String userId = "1";
        private int connectTimeoutMs = 5000;
        /** 单轮对话读超时：LLM 路径实测可到 ~25s，需大于契约用例的全局读超时 */
        private int chatTimeoutMs = 120000;
    }
}
