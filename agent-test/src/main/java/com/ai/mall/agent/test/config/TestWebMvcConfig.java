package com.ai.mall.agent.test.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册测试 Agent 的入口鉴权拦截范围。
 */
@Configuration
@RequiredArgsConstructor
public class TestWebMvcConfig implements WebMvcConfigurer {

    private final TestAccessInterceptor testAccessInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(testAccessInterceptor)
                .addPathPatterns("/api/v1/test/**", "/mcp");
    }
}
