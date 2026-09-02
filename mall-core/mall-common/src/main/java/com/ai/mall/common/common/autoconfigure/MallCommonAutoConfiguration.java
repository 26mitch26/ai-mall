package com.ai.mall.common.common.autoconfigure;

import com.ai.mall.common.common.circuitbreaker.ModelRouterService;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * mall-common 自动配置
 * <p>
 * 背景：agent-customer / agent-ops 的启动类位于 {@code com.ai.mall.agent.*}，
 * 无法扫描到 {@code com.ai.mall.common.*} 下的组件，导致
 * {@link ModelRouterService} 与 {@code RedisTemplate<String, Object>} 注入失败、应用起不来。
 * 此前 RedisTemplate 只能靠引入 mall-security 的 {@code RedisConfig} 获得，属于不合理的强耦合。
 * <p>
 * 方案：通过 Spring Boot 3 的 {@code AutoConfiguration.imports} 机制注册公共 Bean，
 * 并用条件装配保证安全：
 * - 模块未引入 Redis 依赖时，Redis 相关 Bean 直接跳过；
 * - 模块已自定义同名 Bean（如 mall-security 的 RedisConfig）时不覆盖。
 * <p>
 * 注意：本类不能被 @ComponentScan 扫描到，仅由 imports 文件声明。
 */
@AutoConfiguration
public class MallCommonAutoConfiguration {

    /**
     * 模型路由服务：无状态，所有依赖 mall-common 的模块都需要
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelRouterService modelRouterService() {
        return new ModelRouterService();
    }

    /**
     * Redis 模板自动配置
     * <p>
     * 仅在同时满足以下条件时生效：
     * 1. classpath 存在 RedisTemplate（即引入了 spring-boot-starter-data-redis）
     * 2. 容器中存在 RedisConnectionFactory（由 Redis 自动配置提供）
     * 3. 容器中没有同名的 redisTemplate Bean（尊重模块的自定义配置）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisTemplate.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    public static class RedisTemplateConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "redisTemplate")
        public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                           RedisSerializer<Object> redisSerializer) {
            RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
            redisTemplate.setConnectionFactory(connectionFactory);
            redisTemplate.setKeySerializer(new StringRedisSerializer());
            redisTemplate.setValueSerializer(redisSerializer);
            redisTemplate.setHashKeySerializer(new StringRedisSerializer());
            redisTemplate.setHashValueSerializer(redisSerializer);
            redisTemplate.afterPropertiesSet();
            return redisTemplate;
        }

        @Bean
        @ConditionalOnMissingBean(RedisSerializer.class)
        public RedisSerializer<Object> redisSerializer() {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
            // 必须设置，否则反序列化时会丢失类型信息、退化成 Map
            objectMapper.activateDefaultTyping(
                    LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
            return new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);
        }
    }
}
