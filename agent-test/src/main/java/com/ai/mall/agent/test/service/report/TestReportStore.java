package com.ai.mall.agent.test.service.report;

import com.ai.mall.agent.test.model.TestReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class TestReportStore {

    private final ConcurrentMap<String, TestReport> reportStore = new ConcurrentHashMap<>();

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
        log.debug("Stored report: {} (size: {})", report.getId(), reportStore.size());
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
        return reportStore.remove(reportId);
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
        log.debug("Cleared all stored reports");
    }
}