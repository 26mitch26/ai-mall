package com.ai.mall.agent.test.service.report;

import com.ai.mall.agent.test.model.AssertionDetail;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class TestReportGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public TestReport generateReport(String moduleName, List<TestResult> results) {
        log.info("Generating test report for module: {}", moduleName);

        int totalTests = results.size();
        int passedTests = (int) results.stream().filter(TestResult::isPassed).count();
        int failedTests = totalTests - passedTests;
        double passRate = totalTests > 0 ? (double) passedTests / totalTests * 100 : 0;

        // Calculate timing stats
        long totalExecutionTime = results.stream().mapToLong(TestResult::getExecutionTime).sum();
        double averageResponseTime = totalTests > 0 ? (double) totalExecutionTime / totalTests : 0;

        // Calculate assertion stats
        int assertionsTotal = results.stream()
                .mapToInt(r -> r.getAssertionDetails() != null ? r.getAssertionDetails().size() : 0)
                .sum();
        int assertionsPassed = results.stream()
                .flatMap(r -> r.getAssertionDetails() != null ? r.getAssertionDetails().stream() : java.util.stream.Stream.empty())
                .mapToInt(a -> a.isPassed() ? 1 : 0)
                .sum();
        int assertionsFailed = assertionsTotal - assertionsPassed;

        TestReport report = TestReport.builder()
                .id(UUID.randomUUID().toString())
                .moduleName(moduleName)
                .totalTests(totalTests)
                .passedTests(passedTests)
                .failedTests(failedTests)
                .passRate(passRate)
                .averageResponseTime(averageResponseTime)
                .totalExecutionTime(totalExecutionTime)
                .assertionsTotal(assertionsTotal)
                .assertionsPassed(assertionsPassed)
                .assertionsFailed(assertionsFailed)
                .results(results)
                .startTime(results.isEmpty() ? LocalDateTime.now() :
                        results.get(0).getTimestamp())
                .endTime(results.isEmpty() ? LocalDateTime.now() :
                        results.get(results.size() - 1).getTimestamp())
                .build();

        log.info("Test report generated: {} tests, {} passed, {} failed, pass rate: {}%",
                totalTests, passedTests, failedTests, String.format("%.2f", passRate));
        return report;
    }

    public String generateHtmlReport(TestReport report) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html lang=\"zh-CN\">");
        html.append("<head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        html.append("<title>Test Report - ").append(escapeHtml(report.getModuleName())).append("</title>");
        html.append("<style>");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; ");
        html.append("margin: 0; padding: 20px; background: #f5f5f5; color: #333; }");
        html.append(".container { max-width: 1200px; margin: 0 auto; background: white; ");
        html.append("border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); padding: 24px; }");
        html.append("h1 { color: #1a1a2e; border-bottom: 2px solid #e0e0e0; padding-bottom: 12px; }");
        html.append("h2 { color: #16213e; margin-top: 24px; }");
        html.append(".summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); ");
        html.append("gap: 16px; margin: 20px 0; }");
        html.append(".stat-card { background: #f8f9fa; border-radius: 8px; padding: 16px; text-align: center; }");
        html.append(".stat-card .value { font-size: 28px; font-weight: bold; }");
        html.append(".stat-card .label { font-size: 14px; color: #666; margin-top: 4px; }");
        html.append(".pass { color: #28a745; }");
        html.append(".fail { color: #dc3545; }");
        html.append(".warn { color: #ffc107; }");
        html.append("table { width: 100%; border-collapse: collapse; margin-top: 16px; }");
        html.append("th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #e0e0e0; }");
        html.append("th { background: #f8f9fa; font-weight: 600; color: #555; }");
        html.append("tr:hover { background: #f0f0f0; }");
        html.append(".badge { display: inline-block; padding: 2px 8px; border-radius: 12px; ");
        html.append("font-size: 12px; font-weight: 600; }");
        html.append(".badge-pass { background: #d4edda; color: #155724; }");
        html.append(".badge-fail { background: #f8d7da; color: #721c24; }");
        html.append(".assertion-details { margin: 4px 0; padding-left: 16px; font-size: 13px; }");
        html.append(".assertion-pass { color: #28a745; }");
        html.append(".assertion-fail { color: #dc3545; }");
        html.append(".timestamp { color: #999; font-size: 13px; margin-top: 16px; text-align: right; }");
        html.append("</style>");
        html.append("</head>");
        html.append("<body>");
        html.append("<div class=\"container\">");

        // Header
        html.append("<h1>Test Report - ").append(escapeHtml(report.getModuleName())).append("</h1>");

        // Summary cards
        html.append("<div class=\"summary\">");
        html.append("<div class=\"stat-card\"><div class=\"value ").append(report.getPassedTests() > 0 ? "pass" : "").append("\">")
                .append(report.getTotalTests()).append("</div><div class=\"label\">Total Tests</div></div>");
        html.append("<div class=\"stat-card\"><div class=\"value pass\">")
                .append(report.getPassedTests()).append("</div><div class=\"label\">Passed</div></div>");
        html.append("<div class=\"stat-card\"><div class=\"value ").append(report.getFailedTests() > 0 ? "fail" : "").append("\">")
                .append(report.getFailedTests()).append("</div><div class=\"label\">Failed</div></div>");
        html.append("<div class=\"stat-card\"><div class=\"value ").append(report.getPassRate() < 100 ? "warn" : "pass").append("\">")
                .append(String.format("%.1f", report.getPassRate())).append("%</div><div class=\"label\">Pass Rate</div></div>");
        html.append("<div class=\"stat-card\"><div class=\"value\">")
                .append(String.format("%.0f", report.getAverageResponseTime())).append("ms</div><div class=\"label\">Avg Response</div></div>");
        html.append("<div class=\"stat-card\"><div class=\"value\">")
                .append(report.getAssertionsTotal()).append("</div><div class=\"label\">Assertions</div></div>");
        html.append("</div>");

        // Assertion breakdown
        html.append("<h2>Assertion Summary</h2>");
        html.append("<div class=\"summary\">");
        html.append("<div class=\"stat-card\"><div class=\"value pass\">")
                .append(report.getAssertionsPassed()).append("</div><div class=\"label\">Assertions Passed</div></div>");
        html.append("<div class=\"stat-card\"><div class=\"value ").append(report.getAssertionsFailed() > 0 ? "fail" : "").append("\">")
                .append(report.getAssertionsFailed()).append("</div><div class=\"label\">Assertions Failed</div></div>");
        html.append("</div>");

        // Results table
        html.append("<h2>Test Case Details</h2>");
        html.append("<table>");
        html.append("<thead><tr><th>Test Case</th><th>Status</th><th>Status Code</th><th>Time</th><th>Assertions</th></tr></thead>");
        html.append("<tbody>");

        for (TestResult result : report.getResults()) {
            String statusClass = result.isPassed() ? "badge-pass" : "badge-fail";
            String statusText = result.isPassed() ? "PASSED" : "FAILED";

            html.append("<tr>");
            html.append("<td><strong>").append(escapeHtml(result.getTestCaseName() != null ? result.getTestCaseName() : result.getTestCaseId())).append("</strong>");

            // Show assertion details inline
            if (result.getAssertionDetails() != null && !result.getAssertionDetails().isEmpty()) {
                for (AssertionDetail detail : result.getAssertionDetails()) {
                    String assertClass = detail.isPassed() ? "assertion-pass" : "assertion-fail";
                    html.append("<div class=\"assertion-details ").append(assertClass).append("\">");
                    html.append(detail.isPassed() ? "✓" : "✗").append(" ");
                    html.append(escapeHtml(detail.getAssertionName())).append(": ");
                    html.append(escapeHtml(detail.getMessage()));
                    html.append("</div>");
                }
            }
            if (result.getErrorMessage() != null && !result.getErrorMessage().isEmpty()) {
                html.append("<div class=\"assertion-details assertion-fail\">");
                html.append("Error: ").append(escapeHtml(result.getErrorMessage()));
                html.append("</div>");
            }

            html.append("</td>");
            html.append("<td><span class=\"badge ").append(statusClass).append("\">").append(statusText).append("</span></td>");
            html.append("<td>").append(result.getActualStatusCode() > 0 ? result.getActualStatusCode() : "N/A").append("</td>");
            html.append("<td>").append(result.getExecutionTime()).append("ms</td>");
            html.append("<td>").append(result.getAssertionDetails() != null ? result.getAssertionDetails().size() : 0).append("</td>");
            html.append("</tr>");
        }

        html.append("</tbody></table>");

        html.append("<div class=\"timestamp\">");
        html.append("Start: ").append(report.getStartTime() != null ? report.getStartTime().format(DATE_FORMAT) : "N/A").append("<br>");
        html.append("End: ").append(report.getEndTime() != null ? report.getEndTime().format(DATE_FORMAT) : "N/A").append("<br>");
        html.append("Report ID: ").append(escapeHtml(report.getId()));
        html.append("</div>");

        html.append("</div>");
        html.append("</body>");
        html.append("</html>");
        return html.toString();
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}