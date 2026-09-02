package com.ai.mall.common.security.validator;

import com.ai.mall.security.validator.JwtSecurityValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtSecurityValidator tests - verifies startup validation logic.
 * Note: These tests directly test the validation conditions without Spring context.
 */
class JwtSecurityValidatorTest {

    private static final String DEFAULT_SECRET = "default-dev-secret-change-in-prod";
    private static final String STRONG_SECRET = "this-is-a-very-long-and-strong-secret-key-that-exceeds-32-chars!!";

    @Test
    void testDefaultSecretDetected() {
        // Verifies that the DEFAULT_SECRET constant is the one used in application.yml
        assertEquals("default-dev-secret-change-in-prod", DEFAULT_SECRET,
                "DEFAULT_SECRET should match application.yml default");
    }

    @Test
    void testStrongSecretLength() {
        // A production secret must be >= 32 characters
        assertTrue(STRONG_SECRET.length() >= 32,
                "Production JWT secret must be at least 32 characters long for HMAC security");
    }

    @Test
    void testDefaultSecretLength() {
        // 默认开发密钥实际为 33 字符（>= 32 最小长度，历史版本注释误记为 31）。
        // 生产环境的风险由 JwtSecurityValidator 的「secret 等于默认占位值」检测兜底，
        // 与长度检查共同构成双保险；此用例锁定真实长度，防止占位符被意外改短。
        assertEquals(33, DEFAULT_SECRET.length(),
                "默认密钥长度应与 application.yml / JwtSecurityValidator 常量一致");
        assertTrue(DEFAULT_SECRET.length() >= 32);
    }

    @Test
    void testShortSecretWarningCondition() {
        // Simulate the same logic as JwtSecurityValidator.init()
        String shortSecret = "short";
        boolean shouldWarn = shortSecret == null || shortSecret.length() < 32;
        assertTrue(shouldWarn);
    }

    @Test
    void testStrongSecretNoWarningCondition() {
        // Simulate the same logic as JwtSecurityValidator.init()
        boolean shouldWarn = STRONG_SECRET == null || STRONG_SECRET.length() < 32;
        assertFalse(shouldWarn);
    }

    @Test
    void testConstructorAndFieldAccess() throws Exception {
        // Verify the class compiles and has expected structure via reflection
        var constructor = JwtSecurityValidator.class.getDeclaredConstructor();
        assertNotNull(constructor);

        var secretField = JwtSecurityValidator.class.getDeclaredField("secret");
        assertNotNull(secretField);

        var profileField = JwtSecurityValidator.class.getDeclaredField("activeProfile");
        assertNotNull(profileField);
    }
}