package com.ai.mall.agent.test.service.scenario;

import com.ai.mall.agent.test.model.ScenarioCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话场景用例加载器：从 classpath:scenarios/customer-agent-scenarios.json 读取数据驱动的用例定义。
 * <p>
 * 用例与代码分离，新增场景只需追加 JSON（普适性设计：测试意图由业务维护，引擎保持通用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScenarioCaseLoader {

    private static final String SCENARIO_FILE = "scenarios/customer-agent-scenarios.json";

    private final ObjectMapper objectMapper;

    private volatile List<ScenarioCase> cache;

    public List<ScenarioCase> load() {
        if (cache != null) {
            return cache;
        }
        synchronized (this) {
            if (cache != null) {
                return cache;
            }
            cache = doLoad();
            return cache;
        }
    }

    private List<ScenarioCase> doLoad() {
        try (InputStream in = new ClassPathResource(SCENARIO_FILE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            JsonNode scenarios = root.path("scenarios");
            List<ScenarioCase> cases = new ArrayList<>();
            for (JsonNode node : scenarios) {
                cases.add(objectMapper.treeToValue(node, ScenarioCase.class));
            }
            log.info("Loaded {} conversation scenario cases from {}", cases.size(), SCENARIO_FILE);
            return List.copyOf(cases);
        } catch (Exception e) {
            log.error("加载会话场景用例失败: {}", e.getMessage());
            return List.of();
        }
    }
}