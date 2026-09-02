package com.ai.mall.common.security.controller;

import com.ai.mall.common.api.CommonResult;
import com.ai.mall.common.security.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;

/**
 * 统一认证控制器
 * 提供登录、注册、刷新token、登出、获取用户信息等接口，复用于所有业务模块
 * Created by macro on 2018/4/26.
 */
@RestController
@Tag(name = "AuthController", description = "统一认证授权")
@RequestMapping("/auth")
public class AuthController {

    @Value("${jwt.tokenHeader}")
    private String tokenHeader;

    @Autowired
    private AuthService authService;

    @Operation(summary = "用户登录", description = "登录成功后返回token信息，包含token和tokenHead两个字段")
    @PostMapping("/login")
    public CommonResult<Map<String, String>> login(@Validated @RequestBody LoginParam loginParam) {
        Map<String, String> result = authService.login(loginParam.getUsername(), loginParam.getPassword());
        if (result == null) {
            return CommonResult.validateFailed("用户名或密码错误");
        }
        return CommonResult.success(result);
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public CommonResult<?> register(@Validated @RequestBody RegisterParam registerParam) {
        UserDetails userDetails = authService.register(registerParam.getUsername(), registerParam.getPassword());
        if (userDetails == null) {
            return CommonResult.failed("注册失败，用户名已存在");
        }
        return CommonResult.success(null);
    }

    @Operation(summary = "刷新token")
    @PostMapping("/refresh")
    public CommonResult<Map<String, String>> refreshToken(HttpServletRequest request) {
        String token = request.getHeader(tokenHeader);
        Map<String, String> result = authService.refreshToken(token);
        if (result == null) {
            return CommonResult.failed("token已经过期");
        }
        return CommonResult.success(result);
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public CommonResult<?> logout() {
        authService.logout();
        return CommonResult.success(null);
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/info")
    public CommonResult<UserDetails> getAuthInfo(Principal principal) {
        if (principal == null) {
            return CommonResult.unauthorized(null);
        }
        Object authPrincipal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (authPrincipal instanceof UserDetails) {
            return CommonResult.success((UserDetails) authPrincipal);
        }
        return CommonResult.unauthorized(null);
    }

    /**
     * 登录参数
     */
    @Data
    public static class LoginParam {
        @NotEmpty(message = "用户名不能为空")
        private String username;
        @NotEmpty(message = "密码不能为空")
        private String password;
    }

    /**
     * 注册参数
     */
    @Data
    public static class RegisterParam {
        @NotEmpty(message = "用户名不能为空")
        private String username;
        @NotEmpty(message = "密码不能为空")
        private String password;
    }
}
