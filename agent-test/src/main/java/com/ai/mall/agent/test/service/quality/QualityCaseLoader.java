package com.ai.mall.agent.test.service.quality;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.QualityCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量评测集加载器。
 *
 * <p>评测集是 149 条 JSONL（与 agent-customer 召回评测同源），但全量跑一遍要按 LLM 路径
 * 每条 15-25s 计算，超过一小时。因此这里做<b>类别轮询取样</b>：先按 split 与类别过滤，
 * 再按类别轮流取，保证小样本里仍然覆盖政策问答、拒答、注入、边界、冲突五类，
 * 而不是"前 N 条恰好都是政策问答"。
 */
@Slf4j
@Service
public class QualityCaseLoader {

    static final String GOLD_RESOURCE = "evaluation/customer-gold.jsonl";

    private final ObjectMapper objectMapper;
    private final AgentTestConfig config;
    private List<QualityCase> allCases = List.of();

    public QualityCaseLoader(ObjectMapper objectMapper, AgentTestConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    @PostConstruct
    public void load() {
        List<QualityCase> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(GOLD_RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                QualityCase parsed = objectMapper.readValue(line, QualityCase.class);
                if (parsed.getId() != null) {
                    loaded.add(parsed);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load quality gold set from {}: {}", GOLD_RESOURCE, e.getMessage());
        }
        this.allCases = List.copyOf(loaded);
        log.info("Loaded {} quality evaluation cases from {}", allCases.size(), GOLD_RESOURCE);
    }

    public List<QualityCase> all() {
        return allCases;
    }

    /**
     * 按配置筛选评测用例：split + 类别过滤 + 多轮开关 + 类别轮询取样。
     */
    public List<QualityCase> select() {
        AgentTestConfig.QualityConfig quality = config.getQuality();
        List<QualityCase> pool = allCases.stream()
                .filter(c -> matchesSplit(c, quality.getSplit()))
                .filter(c -> matchesCategory(c, quality.getCategories()))
                .filter(c -> quality.isIncludeFollowUp() || !c.isFollowUp())
                .toList();
        return roundRobinSample(pool, quality.getMaxCases());
    }

    private boolean matchesSplit(QualityCase testCase, String split) {
        return split == null || split.isBlank() || split.equalsIgnoreCase(testCase.getSplit());
    }

    private boolean matchesCategory(QualityCase testCase, List<String> categories) {
        return categories == null || categories.isEmpty() || categories.contains(testCase.getCategory());
    }

    /** 类别轮询取样：让有限预算均匀覆盖各能力面，而不是被单一类别占满。 */
    private List<QualityCase> roundRobinSample(List<QualityCase> pool, int maxCases) {
        if (maxCases <= 0 || pool.size() <= maxCases) {
            return pool;
        }
        Map<String, List<QualityCase>> byCategory = new LinkedHashMap<>();
        for (QualityCase testCase : pool) {
            byCategory.computeIfAbsent(testCase.getCategory(), key -> new ArrayList<>()).add(testCase);
        }
        List<QualityCase> sampled = new ArrayList<>(maxCases);
        int index = 0;
        while (sampled.size() < maxCases) {
            boolean progressed = false;
            for (List<QualityCase> bucket : byCategory.values()) {
                if (index < bucket.size()) {
                    sampled.add(bucket.get(index));
                    progressed = true;
                    if (sampled.size() >= maxCases) {
                        break;
                    }
                }
            }
            if (!progressed) {
                break;
            }
            index++;
        }
        return sampled;
    }
}
