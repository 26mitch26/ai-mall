package com.ai.mall.agent.customer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * agent-customer 依赖 mall-common（传递引入 spring-boot-starter-security），
 * 但自定义 SecurityConfig 在 mall-security 模块中（本模块不依赖），因此走的是 Spring Boot
 * 默认安全链——所有请求 401。这里放行对话链路供端到端压测。
 * <p>
 * 注意（压测期真实踩坑）：不要用 {@code requestMatchers("/api/v1/**")} 这种字符串写法。
 * 在 Spring MVC 下它会解析成 {@link org.springframework.security.web.servlet.util.matcher.MvcRequestMatcher}，
 * 其匹配依赖 HandlerMappingIntrospector 动态解析 handler——只有「GET 且该路径真有 Controller 映射」才命中白名单，
 * 导致 POST /api/v1/chat 这类明明已注册的路由被 403（AuthorizationFilter 走 SingleResultAuthorizationManager 拒绝分支）。
 * 统一改用不依赖路由解析的 AntPathRequestMatcher 做纯 URL 匹配，压测稳定性与可解释性都更好。
 * <p>
 * 涉及用户数据的工具仍有工具级鉴权（fail-closed）；生产部署时应收紧或接入统一 JWT 认证。
 */
@Configuration
@EnableWebSecurity
public class AgentSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity.authorizeHttpRequests(registry -> {
            // 纯 URL 前缀匹配（AntPath），对 GET/POST 一视同仁，不依赖 HandlerMappingIntrospector
            registry.requestMatchers(AntPathRequestMatcher.antMatcher("/api/v1/**")).permitAll();
            registry.requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/**")).permitAll();
            registry.requestMatchers(AntPathRequestMatcher.antMatcher("/error")).permitAll();
            registry.requestMatchers(HttpMethod.OPTIONS).permitAll();
            registry.anyRequest().authenticated();
        })
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(configurer -> configurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return httpSecurity.build();
    }
}
