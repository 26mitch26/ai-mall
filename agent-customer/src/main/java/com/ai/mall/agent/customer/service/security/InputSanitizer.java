package com.ai.mall.agent.customer.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
public class InputSanitizer {

    // Maximum input length to prevent token overflow
    private static final int MAX_INPUT_LENGTH = 2000;

    // Patterns that may indicate prompt injection
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
    };

    /**
     * Sanitize user input to prevent prompt injection
     * @param input raw user input
     * @return sanitized input
     */
    public String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        // 1. Truncate to max length
        String sanitized = input.length() > MAX_INPUT_LENGTH
                ? input.substring(0, MAX_INPUT_LENGTH)
                : input;

        // 2. Check for injection patterns
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(sanitized).find()) {
                log.warn("Potential prompt injection detected: {}", sanitized.substring(0, Math.min(100, sanitized.length())));
                return "[BLOCKED: 输入包含不安全内容]";
            }
        }

        // 3. Escape special characters that could break prompt structure
        sanitized = sanitized
                .replace("\\", "\\\\")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\t", " ");

        // 4. Remove null bytes
        sanitized = sanitized.replace("\0", "");

        return sanitized;
    }

    /**
     * Validate input length
     * @param input user input
     * @return true if valid
     */
    public boolean isValidLength(String input) {
        return input != null && input.length() <= MAX_INPUT_LENGTH;
    }

    /**
     * Get sanitized input or default
     * @param input user input
     * @param defaultValue default value
     * @return sanitized input or default
     */
    public String sanitizeOrDefault(String input, String defaultValue) {
        if (input == null || input.trim().isEmpty()) {
            return defaultValue;
        }
        return sanitize(input);
    }
}
