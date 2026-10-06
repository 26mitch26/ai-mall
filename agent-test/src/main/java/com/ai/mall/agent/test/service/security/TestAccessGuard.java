package com.ai.mall.agent.test.service.security;

import com.ai.mall.agent.test.config.AgentTestConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * 入口鉴权守卫（fail-closed）。
 *
 * <p>存在的理由：测试 Agent 的写能力（触发一轮真实 HTTP 回归、safe-demo 关闭时
 * 还会真实写业务数据）此前只靠网关 {@code JwtAuthenticationFilter} 把关，
 * 直连 8085 完全裸奔。MCP 端点一旦对外暴露，攻击面从"网页按钮"扩大到"任意 MCP 客户端"，
 * 因此协议入口本身就要求凭证，而不是像 agent-customer 那样把 MCP 放进网关白名单、
 * 再逐个工具校验——测试工具没有任何一个适合匿名调用。
 *
 * <p>两条凭证通道：
 * <ol>
 *   <li>用户 JWT（与网关 {@code jwt.secret} 同密钥、同算法）——前端与 CI 走这条；</li>
 *   <li>内部静态令牌（{@value #TOKEN_HEADER} 或 {@code Authorization: Bearer <token>}）
 *       ——供外部 Agent（CodeBuddy 等无用户身份的客户端）与自动化脚本使用。</li>
 * </ol>
 * 两者都缺失或无效时返回 401，不存在"默认放行"分支。
 */
@Slf4j
@Service
public class TestAccessGuard {

    /** 内部静态令牌请求头（Agent 客户端可显式使用，避免与用户 JWT 混淆）。 */
    public static final String TOKEN_HEADER = "X-Test-Agent-Token";
    /** 静态令牌通道的调用方标识。 */
    public static final String CALLER_STATIC_TOKEN = "agent-token";
    /** 鉴权关闭时的调用方标识，仅用于本地调试。 */
    public static final String CALLER_AUTH_DISABLED = "auth-disabled";

    private final AgentTestConfig config;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${jwt.tokenHead:Bearer }")
    private String tokenHead;

    private volatile SecretKey signingKey;

    public TestAccessGuard(AgentTestConfig config) {
        this.config = config;
    }

    @PostConstruct
    void init() {
        if (jwtSecret != null && !jwtSecret.isBlank()) {
            // 与网关 JwtAuthenticationFilter#init 保持一致：同一 secret 推导同一算法
            this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        }
        if (config.getAuth().isEnabled()) {
            log.info("Test Agent access control is ENABLED (jwt={}, staticToken={})",
                    config.getAuth().isJwtEnabled() && signingKey != null,
                    config.getAuth().getToken() != null && !config.getAuth().getToken().isBlank());
        } else {
            log.warn("Test Agent access control is DISABLED - /api/v1/test/** and /mcp accept anonymous calls. "
                    + "Set test.agent.auth.enabled=true before exposing this service.");
        }
    }

    public boolean isAuthEnabled() {
        return config.getAuth().isEnabled();
    }

    /**
     * 解析调用方身份；无法通过任何通道时返回空（调用方负责拒绝）。
     *
     * @return 调用方标识（JWT subject / 静态令牌标识 / auth-disabled）
     */
    public Optional<String> authenticate(HttpServletRequest request) {
        if (!config.getAuth().isEnabled()) {
            return Optional.of(CALLER_AUTH_DISABLED);
        }
        String configuredToken = config.getAuth().getToken();
        boolean staticTokenEnabled = configuredToken != null && !configuredToken.isBlank();
        String bearer = bearerToken(request);

        if (staticTokenEnabled) {
            String headerToken = request.getHeader(TOKEN_HEADER);
            if (headerToken != null && secretEquals(headerToken.trim(), configuredToken.trim())) {
                return Optional.of(CALLER_STATIC_TOKEN);
            }
            // 允许 CI / Agent 客户端用标准 Authorization 头携带静态令牌
            if (bearer != null && secretEquals(bearer, configuredToken.trim())) {
                return Optional.of(CALLER_STATIC_TOKEN);
            }
        }

        if (config.getAuth().isJwtEnabled() && signingKey != null && bearer != null) {
            try {
                Claims claims = Jwts.parser().verifyWith(signingKey).build()
                        .parseSignedClaims(bearer).getPayload();
                String subject = claims.getSubject();
                if (subject != null && !subject.isBlank()) {
                    return Optional.of(subject);
                }
            } catch (Exception ex) {
                // 令牌内容与解析异常一律不落日志，避免泄露签名材料
                log.debug("JWT verification failed: {}", ex.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }

    /**
     * 校验调用方身份，失败即 401。
     *
     * @return 调用方标识
     */
    public String requireCaller(HttpServletRequest request) {
        return authenticate(request).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, staticTokenEnabled()
                        ? "测试 Agent 需要有效凭证：请携带用户 JWT，或 " + TOKEN_HEADER + " 内部令牌"
                        : "测试 Agent 需要有效的用户 JWT"));
    }

    /**
     * module 白名单：拒绝任意字符串触发的"对内网系统自动化扫描"。
     *
     * @throws ResponseStatusException 400，module 不在 test.agent.modules 内
     */
    public void validateModule(String module) {
        if (!isSupportedModule(module)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的测试模块：" + module + "，可用模块：" + supportedModules());
        }
    }

    public boolean isSupportedModule(String module) {
        return module != null && supportedModules().contains(module);
    }

    public List<String> supportedModules() {
        List<String> modules = config.getModules();
        return modules == null ? List.of() : List.copyOf(modules);
    }

    private boolean staticTokenEnabled() {
        String token = config.getAuth().getToken();
        return token != null && !token.isBlank();
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        String prefix = tokenHead == null || tokenHead.isBlank() ? "Bearer " : tokenHead;
        if (!header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String token = header.substring(prefix.length()).trim();
        if (token.isEmpty() || token.length() > 8192 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            return null;
        }
        return token;
    }

    /** 常量时间比较，避免通过响应耗时侧信道爆破内部令牌。 */
    private boolean secretEquals(String provided, String expected) {
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
