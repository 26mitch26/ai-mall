package com.ai.mall.agent.test.service.report;

import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告仓库的行为约定：重启后 reportId 仍可查（落盘）、超出保留数淘汰最旧、
 * 落盘截断响应体但不裁剪内存中的完整报告、文件损坏不阻断启动。
 */
class TestReportStoreTest {

    private static final int MAX_RESPONSE_CHARS = 300;

    @TempDir
    Path tempDir;

    private TestReportStore newStore(Path file, int maxRetained) {
        return new TestReportStore(new ObjectMapper(), file.toString(), true, maxRetained, MAX_RESPONSE_CHARS);
    }

    private Path reportFile() {
        return tempDir.resolve("test-reports.json");
    }

    @Test
    void savedReportSurvivesRestart() {
        TestReportStore first = newStore(reportFile(), 20);
        first.save(sampleReport("r1", "mall-portal"));

        TestReportStore restarted = newStore(reportFile(), 20);
        restarted.load();

        TestReport restored = restarted.findById("r1");
        assertNotNull(restored, "报告应落盘，重启后 reportId 仍可查询");
        assertEquals("mall-portal", restored.getModuleName());
        assertEquals(1, restored.getTotalTests());
        assertNotNull(restored.getStartTime());
        assertEquals("GET", restored.getResults().get(0).getMethod());
        assertEquals("/home/content", restored.getResults().get(0).getApiPath());
    }

    @Test
    void retentionEvictsOldestReport() {
        TestReportStore store = newStore(reportFile(), 2);
        store.save(sampleReport("r1", "mall-portal"));
        store.save(sampleReport("r2", "mall-admin"));
        store.save(sampleReport("r3", "mall-portal"));

        assertEquals(2, store.count());
        assertNull(store.findById("r1"), "超出保留数时应淘汰最旧报告");
        assertNotNull(store.findById("r3"));

        TestReportStore restarted = newStore(reportFile(), 20);
        restarted.load();
        assertNull(restarted.findById("r1"), "淘汰结果也应落盘");
        assertNotNull(restarted.findById("r3"));
    }

    @Test
    void diskViewTruncatesResponseButMemoryKeepsIt() {
        String longResponse = "x".repeat(MAX_RESPONSE_CHARS + 500);
        TestReport report = sampleReport("r1", "mall-portal");
        report.getResults().get(0).setActualResponse(longResponse);

        TestReportStore store = newStore(reportFile(), 20);
        store.save(report);
        assertEquals(longResponse, store.findById("r1").getResults().get(0).getActualResponse(),
                "内存中的报告不应被截断");

        TestReportStore restarted = newStore(reportFile(), 20);
        restarted.load();
        String restored = restarted.findById("r1").getResults().get(0).getActualResponse();
        assertTrue(restored.endsWith("...(truncated)"), "落盘视图应截断响应体");
        assertTrue(restored.length() < longResponse.length());
    }

    @Test
    void corruptFileDoesNotBreakStartup() throws IOException {
        Files.writeString(reportFile(), "{ this is not json");
        TestReportStore store = newStore(reportFile(), 20);
        store.load();
        assertEquals(0, store.count());

        store.save(sampleReport("r1", "mall-portal"));
        assertNotNull(store.findById("r1"), "损坏文件被覆盖后应能继续正常写入");
    }

    private TestReport sampleReport(String id, String module) {
        TestResult result = TestResult.builder()
                .testCaseId("c1")
                .testCaseName("normal case GET /home/content")
                .method("GET")
                .apiPath("/home/content")
                .passed(true)
                .actualStatusCode(200)
                .actualResponse("ok")
                .executionTime(15L)
                .build();
        return TestReport.builder()
                .id(id)
                .moduleName(module)
                .totalTests(1)
                .passedTests(1)
                .results(List.of(result))
                .startTime(LocalDateTime.now())
                .endTime(LocalDateTime.now())
                .build();
    }
}
