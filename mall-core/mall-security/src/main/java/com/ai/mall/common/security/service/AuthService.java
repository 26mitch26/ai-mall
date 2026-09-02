package com.ai.mall.common.security.service;

import com.ai.mall.security.util.JwtTokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一认证服务
 * 封装JWT认证流程，提供登录、注册、刷新token、登出等能力，复用于所有业务模块
 * Created by macro on 2018/4/26.
 */
@Service
public class AuthService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

    @Autowired
    private UmsAdminService umsAdminService;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Value("${jwt.tokenHead}")
    private String tokenHead;

    /**
     * 用户登录，验证密码后生成JWT token
     *
     * @param username 用户名
     * @param password 密码
     * @return 认证结果（token + tokenHead），登录失败返回null
     */
    public Map<String, String> login(String username, String password) {
        Map<String, String> result = null;
        try {
            UserDetails userDetails = umsAdminService.loadUserByUsername(username);
            if (!umsAdminService.authenticate(password, userDetails.getPassword())) {
                LOGGER.warn("用户登录密码不正确:{}", username);
                return null;
            }
            if (!userDetails.isEnabled()) {
                LOGGER.warn("用户帐号已被禁用:{}", username);
                return null;
            }
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            String token = jwtTokenUtil.generateToken(userDetails);
            result = new HashMap<>();
            result.put("token", token);
            result.put("tokenHead", tokenHead);
        } catch (Exception e) {
            LOGGER.warn("登录异常:{}", e.getMessage());
        }
        return result;
    }

    /**
     * 刷新token
     *
     * @param oldToken 旧token（含tokenHead前缀）
     * @return 新的认证结果，刷新失败返回null
     */
    public Map<String, String> refreshToken(String oldToken) {
        String refreshToken = jwtTokenUtil.refreshHeadToken(oldToken);
        if (refreshToken == null) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        result.put("token", refreshToken);
        result.put("tokenHead", tokenHead);
        return result;
    }

    /**
     * 登出，清除SecurityContext中的认证信息
     */
    public void logout() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 用户注册，由子类UmsAdminService实现具体注册逻辑
     *
     * @param username 用户名
     * @param password 密码
     * @return 注册后的UserDetails，注册失败返回null
     */
    public UserDetails register(String username, String password) {
        try {
            UserDetails userDetails = umsAdminService.loadUserByUsername(username);
            if (userDetails != null) {
                LOGGER.warn("用户已存在:{}", username);
                return null;
            }
        } catch (Exception ignored) {
            // 用户不存在，可以注册
        }
        return null;
    }

    /**
     * 获取认证结果（token + 用户信息）
     *
     * @param userDetails 用户详情
     * @return 包含token和用户信息的Map
     */
    public Map<String, Object> getAuthResult(UserDetails userDetails) {
        String token = jwtTokenUtil.generateToken(userDetails);
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("tokenHead", tokenHead);
        result.put("username", userDetails.getUsername());
        result.put("authorities", userDetails.getAuthorities());
        return result;
    }
}
