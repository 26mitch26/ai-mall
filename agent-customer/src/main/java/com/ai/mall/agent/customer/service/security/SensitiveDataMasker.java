package com.ai.mall.agent.customer.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏器
 * <p>
 * 从 {@link OutputGuardrail} 中抽出的公共能力，供输出护栏、审计日志、工具返回处理共用，
 * 保证"任何写出去的内容（用户可见回答、日志、审计流水）都不会携带明文 PII"。
 * <p>
 * 覆盖：手机号、身份证号、银行卡号、邮箱。
 */
@Slf4j
@Component
public class SensitiveDataMasker {

    /** 手机号 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 身份证号 */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    /** 银行卡号（16~19 位纯数字） */
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    /** 邮箱 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 对文本中的敏感信息做脱敏，未命中时原样返回。
     *
     * @param text 原始文本
     * @return 脱敏后的文本；入参为 null 时返回 null
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String masked = text;
        // 顺序：先处理位数最长的身份证/银行卡，再处理手机号，避免短模式截断长数字串
        masked = maskByPattern(ID_CARD_PATTERN, masked, this::maskIdCard);
        masked = maskByPattern(BANK_CARD_PATTERN, masked, this::maskBankCard);
        masked = maskByPattern(PHONE_PATTERN, masked, this::maskPhone);
        masked = maskByPattern(EMAIL_PATTERN, masked, this::maskEmail);
        return masked;
    }

    /**
     * 生成用于日志/审计的片段：先脱敏再截断，确保敏感信息不会落盘。
     */
    public String snippet(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String masked = mask(text);
        return masked.length() > maxLength ? masked.substring(0, maxLength) + "..." : masked;
    }

    private String maskByPattern(Pattern pattern, String text, Function<String, String> masker) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            // quoteReplacement 防止替换串中的 $ 和 \ 被当作正则引用
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(masker.apply(matcher.group())));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private String maskIdCard(String idCard) {
        return idCard.substring(0, 6) + "********" + idCard.substring(14);
    }

    private String maskBankCard(String cardNo) {
        return "**** **** **** " + cardNo.substring(cardNo.length() - 4);
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        String name = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        String maskedName = name.length() <= 1 ? "*" : name.charAt(0) + "***";
        return maskedName + domain;
    }
}
