package com.ai.mall.security.validator;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtSecurityValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtSecurityValidator.class);
    private static final String DEFAULT_SECRET = "default-dev-secret-change-in-prod";
    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    @PostConstruct
    public void init() {
        if ("prod".equals(activeProfile)) {
            if (DEFAULT_SECRET.equals(secret)) {
                LOGGER.error("CRITICAL: JWT secret is using default value in production! This is a severe security risk.");
            }
            if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
                LOGGER.warn("JWT secret length is below {} characters. A short secret weakens token security.", MIN_SECRET_LENGTH);
            }
        }
    }
}
