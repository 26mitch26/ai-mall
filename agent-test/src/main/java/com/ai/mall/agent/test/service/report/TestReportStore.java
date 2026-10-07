package com.ai.mall.agent.test.service.report;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试报告仓库：内存索引 + 本地 JSON 落盘。
 *
 * <p>为什么落盘：报告是回归的<b>唯一产物</b>，此前只存内存意味着
 * ① MCP 侧拿到 {@code reportId} 后若进程重启就查不到；
 * ② 前端"历史报告"列表重启即清空，无法做跨轮次对比。
 *
 * <p>落盘的是<b>精简视图</b>：每条用例的响应体按 {@code maxResponseChars} 截断，
 * 完整响应只在内存保留——报告要能长期留存，但不该把整个响应体存成 MB 级文件。
 * 写入采用"临时文件 + 原子移动"，避免进程中断留下半截 JSON。
 */
@Slf4j
@Service
public class TestReportStore {

    static final String DEFAULT_REPORT_FILE = "test-reports.json";

    private final ObjectMapper objectMapper;
    private final String filePath;
    private final boolean persistEnabled;
    private final int maxRetained;
    private final int maxResponseChars;

    private final ConcurrentMap<String, TestReport> reportStore = new ConcurrentHashMap<>();
    private final List<String> insertionOrder = new CopyOnWriteArrayList<>();

    @Autowired
    public TestReportStore(ObjectMapper objectMapper, AgentTestConfig config) {
        this(objectMapper, config.getReport().getFile(), config.getReport().isPersist(),
                config.getReport().getMaxRetained(), config.getReport().getMaxResponseChars());
    }

    /** 测试用：显式指定留存策略。 */
    TestReportStore(ObjectMapper objectMapper, String filePath, boolean persistEnabled,
                    int maxRetained, int maxResponseChars) {
        this.objectMapper = objectMapper.registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.filePath = filePath;
        this.persistEnabled = persistEnabled;
        this.maxRetained = Math.max(1, maxRetained);
        this.maxResponseChars = Math.max(200, maxResponseChars);
    }

    @PostConstruct
    public void load() {
        if (!persistEnabled) {
            return;
        }
        File file = new File(filePath);
        if (!file.exists()) {
            return;
        }
        try {
            List<TestReport> persisted = objectMapper.readValue(file, new TypeReference<List<TestReport>>() { });
            for (TestReport report : persisted) {
                if (report != null && report.getId() != null && reportStore.putIfAbsent(report.getId(), report) == null) {
                    insertionOrder.add(report.getId());
                }
            }
            evictIfNeeded();
            log.info("Loaded {} test reports from {}", reportStore.size(), filePath);
        } catch (IOException e) {
            // 报告文件损坏不应阻断服务启动：内存库仍可继续工作
            log.error("Failed to load test reports from {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Store a test report keyed by its ID.
     *
     * @param report the report to store
     */
    public void save(TestReport report) {
        if (report == null || report.getId() == null) {
            log.warn("Attempted to store null report or report without ID");
            return;
        }
        reportStore.put(report.getId(), report);
        insertionOrder.remove(report.getId());
        insertionOrder.add(report.getId());
        evictIfNeeded();
        log.debug("Stored report: {} (size: {})", report.getId(), reportStore.size());
        persist();
    }

    /**
     * Retrieve a test report by its ID.
     *
     * @param reportId the report ID
     * @return the report, or null if not found
     */
    public TestReport findById(String reportId) {
        return reportStore.get(reportId);
    }

    /**
     * Retrieve all stored test reports.
     *
     * @return collection of all reports
     */
    public Collection<TestReport> findAll() {
        return reportStore.values();
    }

    /**
     * Retrieve all stored test reports as a list.
     *
     * @return list of all reports
     */
    public List<TestReport> findAllAsList() {
        return List.copyOf(reportStore.values());
    }

    /**
     * Remove a test report by its ID.
     *
     * @param reportId the report ID
     * @return the removed report, or null if not found
     */
    public TestReport remove(String reportId) {
        insertionOrder.remove(reportId);
        TestReport removed = reportStore.remove(reportId);
        if (removed != null) {
            persist();
        }
        return removed;
    }

    /**
     * Get the total number of stored reports.
     *
     * @return count of reports
     */
    public int count() {
        return reportStore.size();
    }

    /**
     * Clear all stored reports.
     */
    public void clear() {
        reportStore.clear();
        insertionOrder.clear();
        persist();
    }

    private void evictIfNeeded() {
        while (reportStore.size() > maxRetained) {
            String oldest = null;
            for (String candidate : insertionOrder) {
                if (reportStore.containsKey(candidate)) {
                    oldest = candidate;
                    break;
                }
            }
            if (oldest == null) {
                return;
            }
            if (reportStore.remove(oldest) != null) {
                insertionOrder.remove(oldest);
                log.debug("Evicted oldest test report {} (retention: {})", oldest, maxRetained);
            }
        }
    }

    private void persist() {
        if (!persistEnabled) {
            return;
        }
        File file = new File(filePath);
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            List<TestReport> view = new ArrayList<>();
            for (String id : insertionOrder) {
                TestReport report = reportStore.get(id);
                if (report != null) {
                    view.add(truncateForDisk(report));
                }
            }
            Path temp = Files.createTempFile(file.getParentFile() == null ? Path.of(".") : file.getParentFile().toPath(),
                    "test-reports", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), view);
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to persist test reports to {}: {}", filePath, e.getMessage());
        }
    }

    /** 落盘视图：截断响应体，不影响内存中的完整报告。 */
    private TestReport truncateForDisk(TestReport report) {
        List<TestResult> results = null;
        if (report.getResults() != null) {
            results = new ArrayList<>(report.getResults().size());
            for (TestResult result : report.getResults()) {
                results.add(TestResult.builder()
                        .testCaseId(result.getTestCaseId())
                        .testCaseName(result.getTestCaseName())
                        .method(result.getMethod())
                        .apiPath(result.getApiPath())
                        .passed(result.isPassed())
                        .actualStatusCode(result.getActualStatusCode())
                        .actualResponse(truncate(result.getActualResponse()))
                        .executionTime(result.getExecutionTime())
                        .errorMessage(result.getErrorMessage())
                        .timestamp(result.getTimestamp())
                        .assertionDetails(result.getAssertionDetails())
                        .build());
            }
        }
        return TestReport.builder()
                .id(report.getId())
                .moduleName(report.getModuleName())
                .totalTests(report.getTotalTests())
                .passedTests(report.getPassedTests())
                .failedTests(report.getFailedTests())
                .passRate(report.getPassRate())
                .averageResponseTime(report.getAverageResponseTime())
                .totalExecutionTime(report.getTotalExecutionTime())
                .assertionsTotal(report.getAssertionsTotal())
                .assertionsPassed(report.getAssertionsPassed())
                .assertionsFailed(report.getAssertionsFailed())
                .results(results)
                .knownDefects(report.getKnownDefects())
                .environment(report.getEnvironment())
                .startTime(report.getStartTime())
                .endTime(report.getEndTime())
                .build();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxResponseChars ? value : value.substring(0, maxResponseChars) + "...(truncated)";
    }
}
