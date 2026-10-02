package com.ai.mall.common.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * mall-search 依赖 mall-common（传递引入 spring-boot-starter-security），
 * 但自定义 SecurityConfig 在 mall-security 模块中（本模块不依赖），因此走的是 Spring Boot
 * 默认安全链——所有请求 401。这里放行 ES 搜索链路供压测匿名访问，其余请求仍需认证。
 */
@Configuration
@EnableWebSecurity
public class SearchSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity.authorizeHttpRequests(registry -> {
            registry.requestMatchers("/esProduct/**").permitAll();
            registry.requestMatchers(HttpMethod.OPTIONS).permitAll();
            registry.anyRequest().authenticated();
        })
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(configurer -> configurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return httpSecurity.build();
    }
}