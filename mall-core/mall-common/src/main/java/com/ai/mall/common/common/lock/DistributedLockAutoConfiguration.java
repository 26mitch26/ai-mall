package com.ai.mall.common.lock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 分布式锁自动配置类
 * 当容器中存在RedisTemplate时自动注入DistributedLock Bean
 * Created by macro on 2024/1/1.
 */
@Configuration
@ConditionalOnClass(RedisTemplate.class)
public class DistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnBean(RedisTemplate.class)
    public DistributedLock distributedLock(RedisTemplate<String, Object> redisTemplate) {
        return new DistributedLock(redisTemplate);
    }

    @Bean
    @ConditionalOnBean(DistributedLock.class)
    public DistributedLockAspect distributedLockAspect(DistributedLock distributedLock) {
        return new DistributedLockAspect(distributedLock);
    }
}
