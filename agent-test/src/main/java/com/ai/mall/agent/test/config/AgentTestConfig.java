package com.ai.mall.agent.test.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "test.agent")
public class AgentTestConfig {

    private String baseUrl = "http://localhost:8080";
    private boolean offlineMode = false;
    private int connectTimeout = 5000;
    private int readTimeout = 10000;
    private int maxConnections = 50;
    private int maxConnectionsPerRoute = 25;
    private int responseTimeThresholdMs = 5000;
    private List<String> modules;
    private AiConfig ai = new AiConfig();

    @Data
    public static class AiConfig {
        private boolean enabled = false;
        private String model = "gpt-4o-mini";
        private double temperature = 0.3;
    }
}