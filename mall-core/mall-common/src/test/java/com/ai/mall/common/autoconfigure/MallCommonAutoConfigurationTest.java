package com.ai.mall.common.autoconfigure;

import com.ai.mall.common.circuitbreaker.ModelRouterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动配置测试
 * <p>
 * 验证 mall-common 能在不依赖 mall-security 的前提下提供公共 Bean，
 * 这是 agent-customer / agent-ops 能否启动的前提。
 */
class MallCommonAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MallCommonAutoConfiguration.class));

    @Test
    @DisplayName("自动配置应注册 ModelRouterService")
    void shouldRegisterModelRouterService() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ModelRouterService.class));
    }

    @Test
    @DisplayName("已有同名 Bean 时不覆盖用户自定义配置")
    void shouldNotOverrideExistingModelRouterService() {
        contextRunner
                .withBean("customModelRouter", ModelRouterService.class, ModelRouterService::new)
                .run(context -> assertThat(context).hasBean("customModelRouter"));
    }

    @Test
    @DisplayName("缺少 RedisConnectionFactory 时不注册 RedisTemplate，避免启动失败")
    void shouldSkipRedisTemplateWhenNoConnectionFactory() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(RedisTemplate.class));
    }

    @Test
    @DisplayName("存在 RedisConnectionFactory 时注册 RedisTemplate")
    void shouldRegisterRedisTemplateWhenConnectionFactoryPresent() {
        contextRunner
                .withBean(RedisConnectionFactory.class, () ->
                        org.mockito.Mockito.mock(RedisConnectionFactory.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisTemplate.class);
                    assertThat(context).hasSingleBean(org.springframework.data.redis.serializer.RedisSerializer.class);
                });
    }
}
