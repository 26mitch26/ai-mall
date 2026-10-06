package com.ai.mall.agent.test.service.scenario;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.AssertionDetail;
import com.ai.mall.agent.test.model.ScenarioCase;
import com.ai.mall.agent.test.model.TestResult;
import com.ai.mall.agent.test.service.support.MemberLoginService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客服 Agent 会话场景执行器。
 * <p>
 * 与契约测试（OpenAPI → 路径/参数/状态码）互补，验证会话层能力：
 * 登录会员 → 发送真实问题 → 断言"意图识别、工具取数事实、知识来源、拒答边界、鉴权边界、时延"。
 * 全部为只读问答，不修改业务数据。
 */
@Slf4j
@Service
public class CustomerScenarioRunner {

    /** 场景测试模块名（TestAgent 路由标识） */
    public static final String MODULE_NAME = "agent-customer-scenarios";

    /** 客服对话端点（经网关）：缺陷回流按此路径聚合计数 */
    public static final String CHAT_PATH = "/agent/customer/api/v1/chat";

    private final ScenarioCaseLoader caseLoader;
    private final ObjectMapper objectMapper;
    private final AgentTestConfig config;
    private final MemberLoginService loginService;
    private final RestTemplate http;
    private final AgentTestConfig.ScenarioConfig scenario;

    public CustomerScenarioRunner(ScenarioCaseLoader caseLoader, ObjectMapper objectMapper,
                                  AgentTestConfig config, MemberLoginService loginService) {
        this.caseLoader = caseLoader;
        this.objectMapper = objectMapper;
        this.config = config;
        this.loginService = loginService;
        this.scenario = config.getScenario();
        // 场景含 LLM 路径（政策问答实测可到 ~25s），需要独立的读超时，
        // 不能沿用契约执行器 10s 的全局读超时（会把正常慢回答误判为失败）。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(scenario.getConnectTimeoutMs());
        factory.setReadTimeout(scenario.getChatTimeoutMs());
        this.http = new RestTemplate(factory);
    }

    public boolean supports(String moduleName) {
        return MODULE_NAME.equals(moduleName);
    }

    /**
     * 执行全部会话场景用例，返回与契约用例一致的 TestResult 列表（供统一报告/前端复用）。
     */
    public List<TestResult> run() {
        List<ScenarioCase> cases = caseLoader.load();
        if (cases.isEmpty()) {
            return List.of(failedResult("scenario-suite", "会话场景用例加载",
                    "场景用例为空或加载失败，请检查 " + "scenarios/customer-agent-scenarios.json", ""));
        }
        if (!scenario.isEnabled()) {
            return List.of(failedResult("scenario-suite", "会话场景用例开关",
                    "test.agent.scenario.enabled=false，场景套件已关闭", ""));
        }

        String token = loginService.login();
        if (token == null) {
            String reason = "会员登录失败（" + scenario.getLoginUsername() + "@" + scenario.getGatewayUrl()
                    + "），无法执行需要身份的会话用例";
            List<TestResult> results = new ArrayList<>();
            for (ScenarioCase c : cases) {
                results.add(requiresMemberAuth(c)
                        ? failedResult(c.getId(), c.getName(), reason, "")
                        : executeCase(c, null));
            }
            return results;
        }

        List<TestResult> results = new ArrayList<>();
        for (ScenarioCase c : cases) {
            results.add(executeCase(c, requiresMemberAuth(c) ? token : null));
        }
        return results;
    }

    /** 鉴权判定：除显式声明 "none" 外一律视为需要会员身份（防御 JSON 缺省/空值造成匿名误跑） */
    private boolean requiresMemberAuth(ScenarioCase c) {
        return c.getAuth() == null || c.getAuth().isBlank() || "member".equalsIgnoreCase(c.getAuth());
    }

    // ==================== 用例执行 ====================

    private TestResult executeCase(ScenarioCase c, String token) {
        long start = System.currentTimeMillis();
        List<AssertionDetail> assertions = new ArrayList<>();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", "scenario-" + c.getId() + "-" + System.currentTimeMillis());
            payload.put("message", c.getMessage());
            payload.put("userId", scenario.getUserId());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (token != null) {
                headers.setBearerAuth(token);
            }

            int actualStatus;
            String body;
            try {
                ResponseEntity<String> resp = http.exchange(
                        scenario.getGatewayUrl() + CHAT_PATH,
                        HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
                actualStatus = resp.getStatusCode().value();
                body = resp.getBody();
            } catch (HttpStatusCodeException e) {
                // 鉴权边界用例期望 4xx：这里取真实状态码与响应体继续走断言
                actualStatus = e.getStatusCode().value();
                body = e.getResponseBodyAsString();
            }

            long cost = System.currentTimeMillis() - start;
            int expectedStatus = c.getExpectedHttpStatus() == 0 ? 200 : c.getExpectedHttpStatus();
            addAssertion(assertions, "HTTP 状态码", actualStatus == expectedStatus,
                    String.valueOf(expectedStatus), String.valueOf(actualStatus));

            String answer = "";
            String intent = "";
            int sourceCount = 0;
            if (actualStatus == 200 && body != null && !body.isBlank()) {
                JsonNode root = objectMapper.readTree(body);
                answer = root.path("answer").asText("");
                intent = root.path("intent").asText("");
                JsonNode sources = root.path("sources");
                sourceCount = sources.isArray() ? sources.size() : 0;

                if (c.getExpectedIntent() != null && !c.getExpectedIntent().isBlank()) {
                    addAssertion(assertions, "意图识别", c.getExpectedIntent().equals(intent),
                            c.getExpectedIntent(), intent);
                }
                if (c.getMustContainAll() != null) {
                    for (String kw : c.getMustContainAll()) {
                        addAssertion(assertions, "回答包含[" + kw + "]", answer.contains(kw),
                                "包含 " + kw, answer.contains(kw) ? "包含" : truncate(answer, 120));
                    }
                }
                if (c.getMustContainAny() != null && !c.getMustContainAny().isEmpty()) {
                    boolean hit = c.getMustContainAny().stream().anyMatch(answer::contains);
                    addAssertion(assertions, "回答包含任一" + c.getMustContainAny(), hit,
                            String.join(" / ", c.getMustContainAny()),
                            hit ? "命中" : truncate(answer, 120));
                }
                if (c.getMustNotContain() != null) {
                    for (String kw : c.getMustNotContain()) {
                        boolean absent = !answer.contains(kw);
                        addAssertion(assertions, "回答不含[" + kw + "]", absent,
                                "不出现 " + kw, absent ? "通过" : truncate(answer, 120));
                    }
                }
                if (c.getMinSources() > 0) {
                    addAssertion(assertions, "知识来源≥" + c.getMinSources(), sourceCount >= c.getMinSources(),
                            ">=" + c.getMinSources(), String.valueOf(sourceCount));
                }
            } else if (expectedStatus == 200) {
                // 期望 200 却拿到错误状态：补一条空响应断言，让失败原因可读
                addAssertion(assertions, "响应体可解析", false, "JSON 回答", "HTTP " + actualStatus);
            }
            if (c.getMaxLatencyMs() > 0) {
                addAssertion(assertions, "响应时延<=" + c.getMaxLatencyMs() + "ms", cost <= c.getMaxLatencyMs(),
                        "<=" + c.getMaxLatencyMs() + "ms", cost + "ms");
            }

            boolean passed = assertions.stream().allMatch(AssertionDetail::isPassed);
            String errorMessage = passed ? "" : summarizeFailures(assertions);
            log.info("Scenario {} [{}] -> {} ({}ms, {} assertions)",
                    c.getId(), c.getName(), passed ? "PASSED" : "FAILED", cost, assertions.size());
            return TestResult.builder()
                    .testCaseId(c.getId())
                    .testCaseName(c.getName())
                    .passed(passed)
                    .actualStatusCode(actualStatus)
                    .actualResponse(truncate(answer.isBlank() ? body : answer, 500))
                    .errorMessage(errorMessage)
                    .executionTime(cost)
                    .timestamp(LocalDateTime.now())
                    .assertionDetails(assertions)
                    .build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("Scenario {} failed: {}", c.getId(), e.getMessage());
            return TestResult.builder()
                    .testCaseId(c.getId())
                    .testCaseName(c.getName())
                    .passed(false)
                    .actualStatusCode(0)
                    .actualResponse("")
                    .errorMessage("执行异常: " + e.getMessage())
                    .executionTime(cost)
                    .timestamp(LocalDateTime.now())
                    .assertionDetails(assertions)
                    .build();
        }
    }

    // ==================== 工具方法 ====================

    private void addAssertion(List<AssertionDetail> assertions, String name, boolean passed,
                              String expected, String actual) {
        assertions.add(AssertionDetail.builder()
                .assertionName(name)
                .passed(passed)
                .expected(expected)
                .actual(actual)
                .message(passed ? "通过" : "未通过：" + name + "（期望 " + expected + "，实际 " + actual + "）")
                .build());
    }

    private String summarizeFailures(List<AssertionDetail> assertions) {
        StringBuilder sb = new StringBuilder();
        for (AssertionDetail a : assertions) {
            if (!a.isPassed()) {
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(a.getAssertionName()).append(" 实际=").append(a.getActual());
            }
        }
        return sb.toString();
    }

    private TestResult failedResult(String id, String name, String reason, String response) {
        return TestResult.builder()
                .testCaseId(id)
                .testCaseName(name)
                .passed(false)
                .actualStatusCode(0)
                .actualResponse(response)
                .errorMessage(reason)
                .executionTime(0)
                .timestamp(LocalDateTime.now())
                .assertionDetails(List.of(AssertionDetail.builder()
                        .assertionName("场景套件可用性")
                        .passed(false)
                        .expected("套件可执行")
                        .actual(reason)
                        .message(reason)
                        .build()))
                .build();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}