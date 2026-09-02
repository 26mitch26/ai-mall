package com.ai.mall.agent.customer.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 输入净化（提示词注入防护）
 * <p>
 * 三层防护：
 * 1. 归一化：剥离零宽/不可见字符、全角转半角，消除"拆字混淆"类绕过。
 * 2. 编码还原检测：对 Base64、URL 编码片段先解码再检测，防止把注入指令藏在编码里。
 * 3. 模式匹配：中英文注入指令黑名单，命中即整条拦截。
 * <p>
 * 同时提供 {@link #sanitizeToolObservation(String)} 用于净化"工具返回内容"——
 * 工具返回的数据来自外部系统，同样可能携带注入指令，且会在下一轮被拼回提示词，
 * 属于典型的间接提示词注入（Indirect Prompt Injection）入口。
 */
@Slf4j
@Component
public class InputSanitizer {

    /** 用户输入最大长度，防止 token 溢出与超长攻击 */
    private static final int MAX_INPUT_LENGTH = 2000;

    /** 工具返回内容拼回提示词时的最大长度 */
    private static final int MAX_OBSERVATION_LENGTH = 1500;

    /** 命中注入时的替换文案（对外暴露，便于调用方判定并记录审计） */
    public static final String BLOCKED_MESSAGE = "[BLOCKED: 输入包含不安全内容]";

    /** 工具返回被拦截时的替换文案 */
    private static final String OBSERVATION_BLOCKED_MESSAGE =
            "[已拦截：工具返回内容包含可疑指令，已忽略]";

    /**
     * 注入指令模式（英文）
     */
    private static final Pattern[] INJECTION_PATTERNS = {
            Pattern.compile("(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+a\\s+"),
            Pattern.compile("(?i)act\\s+as\\s+(if\\s+)?you\\s+are"),
            Pattern.compile("(?i)pretend\\s+you\\s+are"),
            Pattern.compile("(?i)forget\\s+(all\\s+)?your\\s+instructions"),
            Pattern.compile("(?i)override\\s+(all\\s+)?safety"),
            Pattern.compile("(?i)disable\\s+(all\\s+)?restrictions"),
            Pattern.compile("(?i)system\\s*:\\s*you\\s+are"),
            Pattern.compile("(?i)<\\|system\\|>"),
            Pattern.compile("(?i)<\\|user\\|>"),
            Pattern.compile("(?i)\\[INST\\]"),
            Pattern.compile("(?i)\\[/INST\\]"),
            Pattern.compile("(?i)\\bdisregard\\s+(all\\s+)?(the\\s+)?(above|prior)"),
            Pattern.compile("(?i)\\breveal\\s+(your\\s+)?(system\\s+)?prompt"),
    };

    /**
     * 注入指令模式（中文）。
     * 早期版本只有英文黑名单，"忽略上面的指令"这类中文注入可直接绕过。
     */
    private static final Pattern[] CN_INJECTION_PATTERNS = {
            Pattern.compile("忽略(上面|以上|之前|前面|上述)(的)?(所有)?(指令|提示|规则|设定)"),
            Pattern.compile("忘记(你)?(的)?(所有)?(指令|提示|规则|设定|身份)"),
            Pattern.compile("你现在是|你现在扮演|请扮演|请充当|假装你是"),
            Pattern.compile("绕过(所有)?(限制|规则|安全|审查)"),
            Pattern.compile("(解除|关闭|取消)(你的)?(所有)?(限制|约束|安全机制)"),
            Pattern.compile("(输出|打印|泄露|显示)(你的)?(系统)?(提示词|指令|prompt)"),
            Pattern.compile("越狱|开发者模式|上帝模式"),
    };

    /** 零宽/不可见字符，用于隐藏关键词绕过检测 */
    private static final Pattern INVISIBLE_CHARS =
            Pattern.compile("[\\u200b\\u200c\\u200d\\u200e\\u200f\\u202a-\\u202e\\u2060\\uFEFF]");

    /** Base64 候选片段（长度足够才尝试解码，避免误判普通单词） */
    private static final Pattern BASE64_CANDIDATE = Pattern.compile("[A-Za-z0-9+/]{16,}={0,2}");

    /** URL 编码片段 */
    private static final Pattern URL_ENCODED = Pattern.compile("%[0-9A-Fa-f]{2}");

    /**
     * 净化用户输入
     *
     * @param input 原始输入
     * @return 净化后的输入；命中注入时返回拦截文案
     */
    public String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        // 1. 截断到最大长度
        String sanitized = input.length() > MAX_INPUT_LENGTH
                ? input.substring(0, MAX_INPUT_LENGTH)
                : input;

        // 2. 归一化（去零宽字符、全角转半角）后再做注入检测，防止拆字绕过
        String normalized = normalize(sanitized);

        // 3. 明文注入检测
        if (matchesInjection(normalized)) {
            log.warn("Potential prompt injection detected: {}", snippet(normalized));
            return BLOCKED_MESSAGE;
        }

        // 4. 编码变形检测（Base64 / URL 编码中藏指令）
        if (containsEncodedInjection(normalized)) {
            log.warn("Potential encoded prompt injection detected: {}", snippet(normalized));
            return BLOCKED_MESSAGE;
        }

        // 5. 转义会破坏提示词结构的特殊字符
        sanitized = sanitized
                .replace("\\", "\\\\")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\t", " ");

        // 6. 去除 null 字节
        return sanitized.replace("\0", "");
    }

    /**
     * 净化工具返回内容，防止间接提示词注入。
     * <p>
     * 与用户输入的区别：工具返回不直接拒绝整轮对话，而是把可疑内容替换为提示文案，
     * 让 Agent 能继续基于"内容不可用"这一事实进行推理。
     *
     * @param observation 工具返回的原始内容
     * @return 净化后的内容，可直接拼回提示词
     */
    public String sanitizeToolObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return "";
        }

        String normalized = normalize(observation);

        if (matchesInjection(normalized) || containsEncodedInjection(normalized)) {
            log.warn("Tool observation blocked due to suspected injection: {}", snippet(normalized));
            return OBSERVATION_BLOCKED_MESSAGE;
        }

        String trimmed = normalized.length() > MAX_OBSERVATION_LENGTH
                ? normalized.substring(0, MAX_OBSERVATION_LENGTH) + "...(已截断)"
                : normalized;

        return trimmed
                .replace("\\", "\\\\")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\t", " ")
                .replace("\0", "");
    }

    /**
     * 校验输入长度
     */
    public boolean isValidLength(String input) {
        return input != null && input.length() <= MAX_INPUT_LENGTH;
    }

    /**
     * 净化输入，空值时返回默认值
     */
    public String sanitizeOrDefault(String input, String defaultValue) {
        if (input == null || input.trim().isEmpty()) {
            return defaultValue;
        }
        return sanitize(input);
    }

    // ==================== 内部方法 ====================

    /**
     * 归一化：剥离不可见字符、全角转半角
     */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String withoutInvisible = INVISIBLE_CHARS.matcher(text).replaceAll("");
        return toHalfWidth(withoutInvisible);
    }

    /**
     * 全角转半角（仅处理 ASCII 对应区间的全角字符）
     */
    private String toHalfWidth(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') {
                builder.append((char) (c - 0xFEE0));
            } else if (c == '\u3000') {
                builder.append(' ');
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * 中英文注入模式匹配
     */
    private boolean matchesInjection(String text) {
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        for (Pattern pattern : CN_INJECTION_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 编码变形检测：把 Base64 / URL 编码内容还原后再次匹配
     */
    private boolean containsEncodedInjection(String text) {
        if (URL_ENCODED.matcher(text).find()) {
            try {
                String decoded = URLDecoder.decode(text, StandardCharsets.UTF_8);
                if (!decoded.equals(text) && matchesInjection(decoded)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // 非法 URL 编码，忽略该检测路径
                log.debug("URL decode failed during injection check");
            }
        }

        var matcher = BASE64_CANDIDATE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            try {
                byte[] decodedBytes = Base64.getDecoder().decode(candidate);
                String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
                if (isPrintable(decoded) && matchesInjection(decoded)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // 不是合法 Base64，继续检查下一个候选片段
                log.debug("Skip non-base64 candidate during injection check");
            }
        }
        return false;
    }

    /**
     * 判断解码结果是否为可读文本（避免把二进制噪音当作文本判断）
     */
    private boolean isPrintable(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int readable = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) || c < 0x80) {
                readable++;
            }
        }
        return (double) readable / text.length() >= 0.9;
    }

    private String snippet(String text) {
        return text.length() > 100 ? text.substring(0, 100) : text;
    }
}
