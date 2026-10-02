package com.ai.mall.agent.test.service.insight;

import com.ai.mall.agent.test.model.KnownDefect;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 已知缺陷经验库：跨会话沉淀"该接口反复失败的高价值信号"。
 * 按接口（METHOD path）聚合信号并持久化到本地 JSON，下次命中时
 * occurrences 递增——回归站在历史经验之上。
 */
@Slf4j
@Service
public class TestInsightStore {

    static final String DEFAULT_INSIGHT_FILE = "test-insights.json";

    private final ObjectMapper objectMapper;
    private final String filePath;
    private final ConcurrentMap<String, KnownDefect> defects = new ConcurrentHashMap<>();

    @Autowired
    public TestInsightStore(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_INSIGHT_FILE);
    }

    /** 测试用：指定持久化路径 */
    TestInsightStore(ObjectMapper objectMapper, String filePath) {
        this.objectMapper = objectMapper.registerModule(new JavaTimeModule());
        this.filePath = filePath;
    }

    @PostConstruct
    public void load() {
        File file = new File(filePath);
        if (!file.exists()) {
            return;
        }
        try {
            List<KnownDefect> persisted = objectMapper.readValue(
                    file, new TypeReference<List<KnownDefect>>() { });
            for (KnownDefect defect : persisted) {
                if (defect.getApiKey() != null) {
                    defects.put(defect.getApiKey(), defect);
                }
            }
            log.info("Loaded {} known defects from {}", defects.size(), filePath);
        } catch (IOException e) {
            log.error("Failed to load insight store from {}: {}", filePath, e.getMessage());
        }
    }

    /** 记录一次高价值失败信号，按接口聚合累计 */
    public synchronized void record(String method, String path, String summary) {
        String apiKey = (method == null ? "" : method.toUpperCase()) + " " + path;
        LocalDateTime now = LocalDateTime.now();
        defects.compute(apiKey, (key, existing) -> {
            if (existing == null) {
                return KnownDefect.builder()
                        .apiKey(apiKey).summary(summary).occurrences(1)
                        .hitThisRun(true).firstSeen(now).lastSeen(now)
                        .build();
            }
            existing.setOccurrences(existing.getOccurrences() + 1);
            existing.setLastSeen(now);
            existing.setHitThisRun(true);
            if (summary != null && !summary.isBlank()) {
                existing.setSummary(summary);
            }
            return existing;
        });
        persist();
    }

    /** 一轮执行结束：清空本轮命中标记 */
    public synchronized void markRoundCompleted() {
        defects.values().forEach(d -> d.setHitThisRun(false));
    }

    /** 全部已知缺陷快照（按命中次数降序） */
    public List<KnownDefect> snapshot() {
        List<KnownDefect> list = new ArrayList<>(defects.values());
        list.sort(Comparator.comparingInt(KnownDefect::getOccurrences).reversed());
        return list;
    }

    /** 查询某接口的历史已知缺陷 */
    public KnownDefect findByApi(String method, String path) {
        return defects.get((method == null ? "" : method.toUpperCase()) + " " + path);
    }

    /** 清空（测试用） */
    public synchronized void clear() {
        defects.clear();
        persist();
    }

    private void persist() {
        try {
            File file = new File(filePath);
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, snapshot());
        } catch (IOException e) {
            log.error("Failed to persist insight store: {}", e.getMessage());
        }
    }
}
