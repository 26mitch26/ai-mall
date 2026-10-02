package com.ai.mall.common.search.config;

import com.ai.mall.common.config.BaseRedisConfig;
import org.springframework.context.annotation.Configuration;

/**
 * Redis配置：mall-search 需要 RedisService（提供商品搜索 Redis 缓存），
 * 而该 bean 定义在 mall-common 的 BaseRedisConfig 中，须有子类配置类才会被实例化。
 * 之前缺失导致启动报 No qualifying bean of type RedisService。
 */
@Configuration
public class RedisConfig extends BaseRedisConfig {

}