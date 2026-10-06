package com.ai.mall.agent.ops.service.audit;

import com.ai.mall.agent.ops.model.GateRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 门控审批单仓库：内存索引 + 本地 JSON 落盘。
 *
 * <p>与 agent-test 的报告仓库同一套持久化风格（原子写、损坏不阻断启动、上限淘汰），
 * 存在的理由一样：审批单是"有人为签字的记录"，重启即丢会让审计链断掉。
 */
@Slf4j
@Service
public class GateDecisionStore {

    private final ObjectMapper objectMapper;
    private final String filePath;
    private final int maxRetained;

    private final ConcurrentMap<String, GateRecord> records = new ConcurrentHashMap<>();

    public GateDecisionStore(ObjectMapper objectMapper,
                             @Value("${aiops.gate.file:aiops-gate-decisions.json}") String filePath,
                             @Value("${aiops.gate.max-retained:200}") int maxRetained) {
        this.objectMapper = objectMapper.registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.filePath = filePath;
        this.maxRetained = Math.max(1, maxRetained);
    }

    @PostConstruct
    public void load() {
        File file = new File(filePath);
        if (!file.exists()) {
            return;
        }
        try {
            List<GateRecord> persisted = objectMapper.readValue(file, new TypeReference<List<GateRecord>>() { });
            for (GateRecord record : persisted) {
                if (record != null && record.getId() != null) {
                    records.put(record.getId(), record);
                }
            }
            log.info("Loaded {} gate records from {}", records.size(), filePath);
        } catch (IOException e) {
            log.error("Failed to load gate records from {}: {}", filePath, e.getMessage());
        }
    }

    public GateRecord save(GateRecord record) {
        records.put(record.getId(), record);
        evictIfNeeded();
        persist();
        return record;
    }

    public Optional<GateRecord> find(String id) {
        return Optional.ofNullable(records.get(id));
    }

    /** 待审批列表（最早创建的排前面，符合审批队列直觉）。 */
    public List<GateRecord> pending() {
        return records.values().stream()
                .filter(record -> "pending_approval".equals(record.getStatus()))
                .sorted(Comparator.comparing(GateRecord::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public List<GateRecord> findAll() {
        return records.values().stream()
                .sorted(Comparator.comparing(GateRecord::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** 当前保留的审批单数量。 */
    public int count() {
        return records.size();
    }

    /** 审批：更新状态并落盘。返回更新后的记录。 */
    public GateRecord decide(String id, String status, String decidedBy, String summary) {
        GateRecord record = records.get(id);
        if (record == null) {
            return null;
        }
        record.setStatus(status);
        record.setDecidedBy(decidedBy);
        record.setDecidedAt(LocalDateTime.now());
        if (summary != null) {
            record.setExecutionSummary(summary);
        }
        persist();
        log.info("Gate {} decided: status={} by={}", id, status, decidedBy);
        return record;
    }

    private void evictIfNeeded() {
        while (records.size() > maxRetained) {
            String oldestId = null;
            LocalDateTime oldest = null;
            for (GateRecord record : records.values()) {
                if (oldest == null || record.getCreatedAt() == null
                        || (record.getCreatedAt() != null && record.getCreatedAt().isBefore(oldest))) {
                    oldestId = record.getId();
                    oldest = record.getCreatedAt();
                }
            }
            if (oldestId == null || records.remove(oldestId) == null) {
                return;
            }
        }
    }

    private void persist() {
        try {
            File file = new File(filePath);
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            List<GateRecord> view = new ArrayList<>(records.values());
            view.sort(Comparator.comparing(GateRecord::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            Path parent = file.getParentFile() == null ? Path.of(".") : file.getParentFile().toPath();
            Path temp = Files.createTempFile(parent, "aiops-gate", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), view);
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to persist gate records to {}: {}", filePath, e.getMessage());
        }
    }

    /** 统计视图：待审批数量与各状态计数。 */
    public Map<String, Object> summary() {
        Map<String, Integer> stats = new java.util.TreeMap<>();
        for (GateRecord record : records.values()) {
            stats.merge(record.getStatus(), 1, Integer::sum);
        }
        return Map.of("total", records.size(), "pending", pending().size(), "byStatus", stats);
    }
}
