package com.ai.mall.common.security.util;

import com.ai.mall.security.util.JwtTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenUtilTest {

    private JwtTokenUtil jwtTokenUtil;

    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_PASSWORD = "password123";
    private static final String TEST_SECRET = "test-secret-key-for-jwt-token-generation-must-be-long-enough";
    private static final String TEST_TOKEN_HEAD = "Bearer ";
    private static final long TEST_EXPIRATION = 3600L;

    @BeforeEach
    void setUp() {
        jwtTokenUtil = new JwtTokenUtil();
        ReflectionTestUtils.setField(jwtTokenUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtTokenUtil, "expiration", TEST_EXPIRATION);
        ReflectionTestUtils.setField(jwtTokenUtil, "tokenHead", TEST_TOKEN_HEAD);
    }

    private UserDetails createUserDetails() {
        return new User(TEST_USERNAME, TEST_PASSWORD,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private void setupSecurityContext(UserDetails userDetails) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    void testGenerateToken() {
        UserDetails userDetails = createUserDetails();
        String token = jwtTokenUtil.generateToken(userDetails);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void testGetUserNameFromToken() {
        UserDetails userDetails = createUserDetails();
        String token = jwtTokenUtil.generateToken(userDetails);
        String username = jwtTokenUtil.getUserNameFromToken(token);
        assertEquals(TEST_USERNAME, username);
    }

    @Test
    void testValidateToken() {
        UserDetails userDetails = createUserDetails();
        setupSecurityContext(userDetails);
        String token = jwtTokenUtil.generateToken(userDetails);
        boolean valid = jwtTokenUtil.validateToken(token, userDetails);
        assertTrue(valid);
    }

    @Test
    void testValidateToken_WrongUser() {
        UserDetails userDetails = createUserDetails();
        UserDetails wrongUser = new User("wronguser", "password",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        String token = jwtTokenUtil.generateToken(userDetails);
        boolean valid = jwtTokenUtil.validateToken(token, wrongUser);
        assertFalse(valid);
    }

    @Test
    void testValidateToken_InvalidToken() {
        UserDetails userDetails = createUserDetails();
        boolean valid = jwtTokenUtil.validateToken("invalid-token", userDetails);
        assertFalse(valid);
    }

    @Test
    void testValidateToken_NullToken() {
        UserDetails userDetails = createUserDetails();
        boolean valid = jwtTokenUtil.validateToken(null, userDetails);
        assertFalse(valid);
    }

    @Test
    void testRefreshHeadToken() {
        UserDetails userDetails = createUserDetails();
        String rawToken = jwtTokenUtil.generateToken(userDetails);
        String oldToken = TEST_TOKEN_HEAD + rawToken;
        String newToken = jwtTokenUtil.refreshHeadToken(oldToken);
        assertNotNull(newToken);
        assertFalse(newToken.isEmpty());
    }

    @Test
    void testRefreshHeadToken_EmptyToken() {
        String newToken = jwtTokenUtil.refreshHeadToken("");
        assertNull(newToken);
    }

    @Test
    void testRefreshHeadToken_NullToken() {
        String newToken = jwtTokenUtil.refreshHeadToken(null);
        assertNull(newToken);
    }

    @Test
    void testRefreshHeadToken_InvalidToken() {
        String oldToken = TEST_TOKEN_HEAD + "invalid.jwt.token";
        String newToken = jwtTokenUtil.refreshHeadToken(oldToken);
        assertNull(newToken);
    }

    @Test
    void testGetUserNameFromToken_InvalidToken() {
        String username = jwtTokenUtil.getUserNameFromToken("invalid.token.string");
        assertNull(username);
    }

    @Test
    void testGetUserNameFromToken_EmptyToken() {
        String username = jwtTokenUtil.getUserNameFromToken("");
        assertNull(username);
    }

    @Test
    void testGenerateToken_DifferentUsers() {
        UserDetails user1 = new User("user1", "pass1",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        UserDetails user2 = new User("user2", "pass2",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        String token1 = jwtTokenUtil.generateToken(user1);
        String token2 = jwtTokenUtil.generateToken(user2);

        assertNotEquals(token1, token2);
        assertEquals("user1", jwtTokenUtil.getUserNameFromToken(token1));
        assertEquals("user2", jwtTokenUtil.getUserNameFromToken(token2));
    }

    @Test
    void testPayloadTimestamps() throws Exception {
        UserDetails userDetails = createUserDetails();
        String token = jwtTokenUtil.generateToken(userDetails);

        // Use reflection to call getPayloadFromToken for internal verification
        Method getPayloadMethod = JwtTokenUtil.class.getDeclaredMethod("getPayloadFromToken", String.class);
        getPayloadMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) getPayloadMethod.invoke(jwtTokenUtil, token);

        assertNotNull(payload);
        assertEquals(TEST_USERNAME, payload.get("sub"));
        assertNotNull(payload.get("created"));
        assertNotNull(payload.get("exp"));
        assertTrue(((Number) payload.get("exp")).longValue() > System.currentTimeMillis());
    }
}