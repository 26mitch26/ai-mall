package com.ai.mall.agent.test.service.security;

import com.ai.mall.agent.test.config.AgentTestConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入口鉴权守卫的行为约定：任何缺失/错误凭证都必须 fail-closed；
 * 用户 JWT 与内部静态令牌是并列的两条通道，任一成立即放行。
 */
class TestAccessGuardTest {

    private static final String SECRET = "unit-test-secret-of-sufficient-length";
    private static final String STATIC_TOKEN = "internal-agent-token";

    private AgentTestConfig config;
    private TestAccessGuard guard;

    @BeforeEach
    void setUp() {
        config = new AgentTestConfig();
        config.setModules(List.of("mall-admin", "mall-portal", "agent-customer-scenarios"));
        config.getAuth().setEnabled(true);
        config.getAuth().setJwtEnabled(true);
        config.getAuth().setToken(STATIC_TOKEN);
        guard = newGuard();
    }

    private TestAccessGuard newGuard() {
        TestAccessGuard created = new TestAccessGuard(config);
        ReflectionTestUtils.setField(created, "jwtSecret", SECRET);
        created.init();
        return created;
    }

    private String jwt(String subject, long ttlMillis) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().subject(subject)
                .expiration(new Date(System.currentTimeMillis() + ttlMillis))
                .signWith(key).compact();
    }

    @Test
    void rejectsRequestWithoutAnyCredential() {
        assertTrue(guard.authenticate(new MockHttpServletRequest()).isEmpty());
    }

    @Test
    void rejectsWrongStaticToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TestAccessGuard.TOKEN_HEADER, "wrong-token");
        assertTrue(guard.authenticate(request).isEmpty());
    }

    @Test
    void acceptsStaticTokenHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TestAccessGuard.TOKEN_HEADER, STATIC_TOKEN);
        assertEquals(TestAccessGuard.CALLER_STATIC_TOKEN, guard.authenticate(request).orElseThrow());
    }

    @Test
    void acceptsStaticTokenAsBearerCredential() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + STATIC_TOKEN);
        assertEquals(TestAccessGuard.CALLER_STATIC_TOKEN, guard.authenticate(request).orElseThrow());
    }

    @Test
    void acceptsValidUserJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt("admin", 60_000L));
        assertEquals("admin", guard.authenticate(request).orElseThrow());
    }

    @Test
    void rejectsTamperedJwt() {
        String valid = jwt("admin", 60_000L);
        String tampered = valid.substring(0, valid.length() - 3) + "abc";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tampered);
        assertTrue(guard.authenticate(request).isEmpty());
    }

    @Test
    void rejectsExpiredJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt("admin", -1_000L));
        assertTrue(guard.authenticate(request).isEmpty());
    }

    @Test
    void jwtChannelCanBeDisabled() {
        config.getAuth().setJwtEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt("admin", 60_000L));
        assertTrue(newGuard().authenticate(request).isEmpty());
    }

    @Test
    void staticTokenChannelStaysAvailableWithoutJwtConfiguration() {
        config.getAuth().setJwtEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TestAccessGuard.TOKEN_HEADER, STATIC_TOKEN);
        assertEquals(TestAccessGuard.CALLER_STATIC_TOKEN, newGuard().authenticate(request).orElseThrow());
    }

    @Test
    void disabledAuthShortCircuitsToNamedCaller() {
        config.getAuth().setEnabled(false);
        assertEquals(TestAccessGuard.CALLER_AUTH_DISABLED, newGuard()
                .authenticate(new MockHttpServletRequest()).orElseThrow());
    }

    @Test
    void requireCallerThrowsUnauthorizedWithoutCredential() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> guard.requireCaller(new MockHttpServletRequest()));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void moduleWhitelistRejectsUnknownModule() {
        guard.validateModule("mall-portal");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> guard.validateModule("mall-search"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
