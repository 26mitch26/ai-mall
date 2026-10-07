package com.ai.mall.agent.test.controller;

import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.model.TestReportComparison;
import com.ai.mall.agent.test.service.agent.TestAgent;
import com.ai.mall.agent.test.service.report.TestReportGenerator;
import com.ai.mall.agent.test.service.report.TestReportStore;
import com.ai.mall.agent.test.service.report.TestReportComparisonService;
import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.config.TestAccessInterceptor;
import com.ai.mall.agent.test.service.security.TestAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
@Tag(name = "自动化测试", description = "自动化测试接口")
public class TestController {

    private final TestAgent testAgent;
    private final TestReportGenerator reportGenerator;
    private final TestReportStore reportStore;
    private final TestReportComparisonService comparisonService;
    private final AgentTestConfig config;
    private final TestAccessGuard accessGuard;

    @GetMapping("/capabilities")
    @Operation(summary = "测试 Agent 能力", description = "返回契约测试引擎与本地模型信息")
    public Map<String, Object> capabilities() {
        return Map.of(
                "online", true,
                "modules", config.getModules(),
                "aiEnabled", config.getAi().isEnabled(),
                "model", config.getAi().getModel(),
                "authRequired", accessGuard.isAuthEnabled(),
                "mcpEnabled", config.getMcp().isEnabled(),
                "pipeline", List.of("OpenAPI Discover", "Contract Cases", "HTTP Execute", "Assertions", "Report")
        );
    }

    @PostMapping("/generate")
    @Operation(summary = "生成并运行测试用例", description = "为指定模块自动发现API并生成/运行测试用例")
    public TestReport generateAndRunTests(@RequestParam String module,
                                          @RequestAttribute(value = TestAccessInterceptor.CALLER_ATTRIBUTE,
                                                  required = false) String caller) {
        // module 白名单：拒绝任意字符串把服务变成"对内网系统的自动化扫描器"
        accessGuard.validateModule(module);
        log.info("[{}] Generating and running tests for module: {}", caller, module);
        return testAgent.runTests(module);
    }

    @GetMapping("/reports")
    @Operation(summary = "获取所有测试报告", description = "获取所有已存储的测试报告列表")
    public List<TestReport> getAllReports() {
        log.debug("Fetching all test reports");
        return reportStore.findAllAsList();
    }

    @GetMapping("/reports/compare")
    @Operation(summary = "比较两轮测试报告", description = "按稳定身份标出新增失败、修复、持续失败和用例增删；环境未验证时不作业务结论")
    public TestReportComparison compareReports(@RequestParam String baselineId,
                                               @RequestParam String currentId) {
        if (baselineId.isBlank() || currentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "两份报告 ID 必须填写");
        }
        if (reportStore.findById(baselineId) == null || reportStore.findById(currentId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在或已被保留策略淘汰，请重新选择基线");
        }
        try {
            return comparisonService.compare(baselineId, currentId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        }
    }

    @GetMapping("/report/{reportId}")
    @Operation(summary = "获取JSON格式报告", description = "获取指定测试报告的JSON格式")
    public ResponseEntity<TestReport> getReport(@PathVariable String reportId) {
        log.debug("Fetching report: {}", reportId);
        TestReport report = reportStore.findById(reportId);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/report/{reportId}/html")
    @Operation(summary = "获取HTML格式报告", description = "获取指定测试报告的HTML格式")
    public ResponseEntity<String> getHtmlReport(@PathVariable String reportId) {
        log.debug("Fetching HTML report: {}", reportId);
        TestReport report = reportStore.findById(reportId);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }

        String html = reportGenerator.generateHtmlReport(report);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + "; charset=utf-8")
                .body(html);
    }
}
