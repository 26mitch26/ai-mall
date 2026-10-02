package com.ai.mall.agent.test.service.generator;

import com.ai.mall.agent.test.model.ApiDefinition;
import com.ai.mall.agent.test.model.Parameter;
import com.ai.mall.agent.test.model.Response;
import com.ai.mall.agent.test.model.TestCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 测试用例契约守卫：让期望值全部锚定 OpenAPI 契约，而非硬编码假设或 LLM 猜测。
 *
 * <h3>为什么需要它</h3>
 * 自动生成测试用例的根本难题是 oracle problem——"用例对不对"取决于期望值是否可信。
 * 本类把期望值的唯一合法来源定为 API 文档的 responses 声明：
 * <ul>
 *   <li>正向用例：期望码 = 文档声明的成功码；文档未声明成功响应 → 无 oracle，用例作废；</li>
 *   <li>负向用例：文档声明了该错误码 → 精确断言；未声明 → 降级为弱断言
 *       （仅要求服务端以 4xx 拒绝），因为"返回哪个 4xx"是框架实现细节而非契约；</li>
 *   <li>AI 产出：额外校验方法/路径/参数名与文档一致，拦截 LLM 幻觉
 *       （编造相邻路径、捏造参数名、猜测无背书的状态码）。</li>
 * </ul>
 *
 * <h3>与生成器的关系</h3>
 * 规则生成器与 AI 生成器都在产出后调用本类对齐/拦截，期望语义单一出处；
 * 执行器依据 {@link TestCase#isStrictExpectation()} 选择精确或弱断言。
 */
@Slf4j
@Service
public class TestCaseContractGuard {

    /**
     * 批量对齐规则生成的用例：按期望状态码区分正向/负向分别处理。
     * 正向用例在文档无成功声明时作废；其余保留（含降级为弱断言的负向用例）。
     */
    public List<TestCase> align(List<TestCase> cases, ApiDefinition api) {
        List<TestCase> aligned = new ArrayList<>();
        for (TestCase tc : cases) {
            if (tc == null) {
                continue;
            }
            TestCase fixed = tc.getExpectedStatusCode() < 400
                    ? alignSuccess(tc, api)
                    : alignRejection(tc, api);
            if (fixed != null) {
                aligned.add(fixed);
            } else {
                log.warn("Dropped case without contract oracle: [{}] {} {} (no success status declared)",
                        tc.getName(), tc.getMethod(), tc.getApiPath());
            }
        }
        return aligned;
    }

    /**
     * 正向用例对齐：期望码锚定为文档声明的成功码。
     * 文档未声明任何成功响应时没有可依据的期望，返回 null 表示作废。
     */
    public TestCase alignSuccess(TestCase tc, ApiDefinition api) {
        return api.declaredSuccessCode()
                .map(code -> applyContract(tc, api, code, true))
                .orElse(null);
    }

    /**
     * 负向用例对齐：文档声明了该错误码 → 精确断言；未声明 → 弱断言（4xx 拒绝）。
     */
    public TestCase alignRejection(TestCase tc, ApiDefinition api) {
        int status = tc.getExpectedStatusCode();
        boolean declared = api.declaredStatusCodes().contains(status);
        return applyContract(tc, api, status, declared);
    }

    /**
     * 统一应用契约：设置期望码与断言强度，并回填该状态码的声明响应 schema
     * （供执行器做 OpenAPI Schema 结构断言；未声明则为 null，执行器跳过）。
     */
    private TestCase applyContract(TestCase tc, ApiDefinition api, int status, boolean strict) {
        tc.withExpectation(status, strict);
        Response declared = api.getResponses() != null
                ? api.getResponses().get(String.valueOf(status))
                : null;
        tc.setResponseSchema(declared != null ? declared.getSchema() : null);
        return tc;
    }

    /**
     * AI 产出契约守卫：逐条校验，不合法返回 empty 表示丢弃。
     *
     * <p>拦截三类 LLM 常见幻觉：
     * <ol>
     *   <li>编造路径/方法：用例的目标必须就是当前被测 API；</li>
     *   <li>捏造参数名：请求参数必须 ⊆ 文档声明的参数集；</li>
     *   <li>无背书状态码：2xx/5xx 未在文档声明 → 丢弃（正向期望必须来自契约，
     *       服务端 5xx 属于故障而非可断言行为）；未声明的 4xx → 降级弱断言保留。</li>
     * </ol>
     */
    public Optional<TestCase> guardAiCase(TestCase tc, ApiDefinition api) {
        if (tc.getMethod() == null || !tc.getMethod().equalsIgnoreCase(api.getMethod())) {
            log.debug("[Schema-Guard] Dropped AI case with mismatched method: {} (expected {})",
                    tc.getMethod(), api.getMethod());
            return Optional.empty();
        }
        if (tc.getApiPath() == null || !tc.getApiPath().equals(api.getPath())) {
            log.debug("[Schema-Guard] Dropped AI case with hallucinated path: {} (expected {})",
                    tc.getApiPath(), api.getPath());
            return Optional.empty();
        }
        if (tc.getRequestParams() != null
                && !allowedParamNames(api).containsAll(tc.getRequestParams().keySet())) {
            log.debug("[Schema-Guard] Dropped AI case with hallucinated params: {}",
                    tc.getRequestParams().keySet());
            return Optional.empty();
        }

        int status = tc.getExpectedStatusCode();
        if (api.declaredStatusCodes().contains(status)) {
            return Optional.of(applyContract(tc, api, status, true));
        }
        if (status >= 400 && status < 500) {
            log.debug("[Schema-Guard] Status {} not declared, downgraded to 4xx weak assertion", status);
            return Optional.of(applyContract(tc, api, status, false));
        }
        log.debug("[Schema-Guard] Dropped AI case with unsupported status: {}", status);
        return Optional.empty();
    }

    /**
     * 该 API 允许出现的参数名集合（query/path/header/body 全部声明参数 + body 占位名）。
     */
    private Set<String> allowedParamNames(ApiDefinition api) {
        Set<String> allowed = new HashSet<>();
        if (api.getParameters() != null) {
            for (Parameter p : api.getParameters()) {
                allowed.add(p.getName());
            }
        }
        allowed.add("requestBody");
        return allowed;
    }
}
