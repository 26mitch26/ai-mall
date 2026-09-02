package com.ai.mall.agent.customer.service.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 输出护栏（Output Guardrail）
 * <p>
 * 与 {@link InputSanitizer} 相对，本类负责"出口"侧的安全与可信校验：
 * 模型最终返回给用户的内容，必须再过一道检查，才能落地。
 * <p>
 * 拦截/处理的风险类型：
 * 1. EMPTY                —— 模型返回空内容，直接拒答，避免前端出现空白或异常
 * 2. INTERNAL_MARKER_LEAK —— 回答中泄露了 ReAct 内部推理结构（Thought/Action/Final Answer 等），
 *                            通常是模型格式失控或被 prompt injection 诱导，必须拦截
 * 3. UNSOURCED_FACT       —— 在无任何事实来源（未调用工具、知识库也无命中）的情况下，
 *                            却给出了订单号、金额等具体事实，属于典型的模型幻觉，必须拦截
 * 4. SENSITIVE_INFO       —— 回答中包含手机号/身份证/银行卡/邮箱等 PII，做脱敏后放行
 * 5. TRUNCATED            —— 回答超过最大长度，截断后放行
 * <p>
 * 设计原则：
 * - 拦得住"编造"：具体事实必须有工具返回值或知识库检索结果作为支撑
 * - 不误伤正常闲聊：仅当出现具体事实且无来源时才拦截，打招呼、流程说明等不受影响
 * - 敏感信息只脱敏不落库：日志中输出的永远是脱敏后的内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutputGuardrail {

    /** 敏感信息脱敏器，保证用户可见回答与审计日志使用同一套脱敏规则 */
    private final SensitiveDataMasker sensitiveDataMasker;

    /** 单条回答的最大长度，超过则截断，避免异常超长输出 */
    private static final int MAX_OUTPUT_LENGTH = 4000;

    /** 日志中最多打印的字符数 */
    private static final int LOG_SNIPPET_LENGTH = 100;

    // ==================== 拒答兜底话术 ====================

    public static final String NO_ANSWER_FALLBACK =
            "抱歉，我暂时无法回答这个问题，已为您转接人工客服。";

    public static final String INTERNAL_LEAK_FALLBACK =
            "抱歉，刚才的回答出现异常，已为您转接人工客服。";

    public static final String UNSOURCED_FACT_FALLBACK =
            "抱歉，订单、金额这类具体信息我无法凭空确认，请以系统实际数据为准，或联系人工客服为您核实。";

    // ==================== 风险类型 ====================

    public enum RiskType {
        /** 无风险 */
        NONE,
        /** 空回答 */
        EMPTY,
        /** 泄露内部推理标记 */
        INTERNAL_MARKER_LEAK,
        /** 无事实来源的具体事实（疑似幻觉） */
        UNSOURCED_FACT,
        /** 含敏感信息，已脱敏 */
        SENSITIVE_INFO_MASKED,
        /** 超长，已截断 */
        TRUNCATED
    }

    // ==================== 风险检测正则 ====================

    /**
     * 内部推理标记：正常客服回答不应出现这些结构词
     */
    private static final Pattern[] INTERNAL_MARKER_PATTERNS = {
            Pattern.compile("(?i)\\bThought\\s*:"),
            Pattern.compile("(?i)\\bAction\\s*Input\\s*:"),
            Pattern.compile("(?i)\\bFinal\\s*Answer\\s*:"),
            Pattern.compile("(?i)\\bObservation\\s*:"),
            Pattern.compile("可用工具\\s*[:：]"),
            Pattern.compile("ReAct\\s*[（(]"),
            Pattern.compile("(?i)\\bsystem\\s*prompt\\s*:"),
    };

    /**
     * 金额：¥199 / 199.00元 / 199 块钱
     */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "[¥￥]\\s*\\d+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?\\s*(?:元|块钱|块|RMB|CNY)");

    /**
     * 订单类编号：带前缀的 8 位以上编号，或 12 位以上纯数字。
     * 刻意避开"订单123456"这类短编号，避免误伤用户自己说出的口语化单号。
     */
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile(
            "(?:订单号|订单编号|运单号|快递单号|物流单号|流水号|退款单号)\\s*[:：]?\\s*[A-Za-z0-9\\-]{8,}|\\b\\d{12,}\\b");

    // 手机号/身份证/银行卡/邮箱的识别与脱敏统一由 SensitiveDataMasker 负责，
    // 避免在护栏与审计两处维护两套正则。

    // ==================== 结果封装 ====================

    @Getter
    public static class GuardrailResult {

        /** 是否允许原样/处理后返回给用户（false 表示已被拦截，answer 为兜底话术） */
        private final boolean allowed;
        /** 最终返回给用户的内容 */
        private final String answer;
        /** 命中的风险类型 */
        private final RiskType riskType;
        /** 判定原因，便于排查与审计 */
        private final String reason;

        private GuardrailResult(boolean allowed, String answer, RiskType riskType, String reason) {
            this.allowed = allowed;
            this.answer = answer;
            this.riskType = riskType;
            this.reason = reason;
        }

        public static GuardrailResult pass(String answer) {
            return new GuardrailResult(true, answer, RiskType.NONE, "无风险");
        }

        public static GuardrailResult pass(String answer, RiskType riskType, String reason) {
            return new GuardrailResult(true, answer, riskType, reason);
        }

        public static GuardrailResult block(String answer, RiskType riskType, String reason) {
            return new GuardrailResult(false, answer, riskType, reason);
        }
    }

    // ==================== 主校验入口 ====================

    /**
     * 对模型输出做安全与可信校验
     *
     * @param answer       模型生成的原始回答
     * @param hasGrounding 本次回答是否有事实来源支撑（调用过工具且拿到返回值，或知识库有命中）
     * @return 校验结果，调用方应使用 {@link GuardrailResult#getAnswer()} 作为最终回答
     */
    public GuardrailResult check(String answer, boolean hasGrounding) {
        // 1. 空回答
        if (answer == null || answer.isBlank()) {
            log.warn("输出护栏拦截[EMPTY]: 模型返回空内容");
            return GuardrailResult.block(NO_ANSWER_FALLBACK, RiskType.EMPTY, "模型返回空内容");
        }

        // 2. 超长截断（先截断，避免后续正则在超大文本上执行）
        boolean truncated = answer.length() > MAX_OUTPUT_LENGTH;
        String processed = truncated ? answer.substring(0, MAX_OUTPUT_LENGTH) : answer;

        // 3. 内部推理标记泄露
        if (containsInternalMarker(processed)) {
            log.warn("输出护栏拦截[INTERNAL_MARKER_LEAK]: 回答泄露内部推理结构, snippet={}",
                    safeSnippet(processed));
            return GuardrailResult.block(INTERNAL_LEAK_FALLBACK, RiskType.INTERNAL_MARKER_LEAK,
                    "回答中泄露了内部推理结构，疑似格式失控或被注入");
        }

        // 4. 无事实来源的具体事实 —— 防幻觉的核心规则
        if (!hasGrounding && containsConcreteFact(processed)) {
            log.warn("输出护栏拦截[UNSOURCED_FACT]: 无事实来源却给出具体金额/单号, snippet={}",
                    safeSnippet(processed));
            return GuardrailResult.block(UNSOURCED_FACT_FALLBACK, RiskType.UNSOURCED_FACT,
                    "回答包含未经工具或知识库确认的具体事实");
        }

        // 5. 敏感信息脱敏后放行
        String masked = maskSensitiveInfo(processed);
        if (!masked.equals(processed)) {
            log.warn("输出护栏[SENSITIVE_INFO]: 回答包含敏感信息，已脱敏后返回");
            return GuardrailResult.pass(masked, RiskType.SENSITIVE_INFO_MASKED,
                    "回答中包含敏感信息，已做脱敏处理");
        }

        // 6. 超长截断后放行
        if (truncated) {
            log.warn("输出护栏[TRUNCATED]: 回答超过{}字符，已截断", MAX_OUTPUT_LENGTH);
            return GuardrailResult.pass(masked, RiskType.TRUNCATED, "回答超过最大长度，已截断");
        }

        return GuardrailResult.pass(masked);
    }

    // ==================== 内部检测方法 ====================

    /**
     * 是否包含内部推理标记
     */
    private boolean containsInternalMarker(String text) {
        for (Pattern pattern : INTERNAL_MARKER_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否包含需要事实来源支撑的具体事实（金额 / 订单类编号）
     */
    private boolean containsConcreteFact(String text) {
        return AMOUNT_PATTERN.matcher(text).find() || ORDER_NO_PATTERN.matcher(text).find();
    }

    /**
     * 对回答中的 PII 做脱敏（委托公共脱敏器）
     */
    private String maskSensitiveInfo(String text) {
        return sensitiveDataMasker.mask(text);
    }

    /**
     * 生成用于日志的片段：先脱敏再截断，确保敏感信息不会落盘到日志
     */
    private String safeSnippet(String text) {
        return sensitiveDataMasker.snippet(text, LOG_SNIPPET_LENGTH);
    }
}
