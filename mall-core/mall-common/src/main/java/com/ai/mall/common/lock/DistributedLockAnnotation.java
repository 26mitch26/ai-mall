package com.ai.mall.common.lock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式分布式锁注解
 * 在方法上使用，自动加锁/解锁
 * <p>
 * 使用示例：
 * <pre>
 *   &#64;DistributedLock(key = "order:#id", expire = 30, waitTime = 5000)
 *   public void createOrder(Long id) { ... }
 * </pre>
 * <p>
 * key支持SpEL表达式，使用 # 引用方法参数，如 #id、#user.name
 * Created by macro on 2024/1/1.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLockAnnotation {

    /**
     * 锁的key，支持SpEL表达式
     * 使用 # 引用方法参数，如 "order:#id"、"user:#user.id"
     */
    String key();

    /**
     * 锁过期时间(秒)，默认30秒
     */
    long expire() default 30;

    /**
     * 等待获取锁的最大时间(毫秒)，默认0表示不等待
     */
    long waitTime() default 0;

    /**
     * 是否启用看门狗自动续期，默认false
     */
    boolean watchdog() default false;
}
