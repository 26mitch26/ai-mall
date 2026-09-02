package com.ai.mall.common.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

/**
 * 分布式锁AOP切面
 * 拦截@DistributedLockAnnotation注解，实现声明式分布式锁
 * 支持SpEL表达式解析key
 * Created by macro on 2024/1/1.
 */
@Aspect
public class DistributedLockAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedLockAspect.class);

    private final DistributedLock distributedLock;

    private final ExpressionParser spelParser = new SpelExpressionParser();

    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public DistributedLockAspect(DistributedLock distributedLock) {
        this.distributedLock = distributedLock;
    }

    @Around("@annotation(com.ai.mall.common.lock.DistributedLockAnnotation)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DistributedLockAnnotation annotation = method.getAnnotation(DistributedLockAnnotation.class);

        // 解析SpEL表达式，生成实际的lockKey
        String lockKey = parseSpelKey(annotation.key(), method, joinPoint.getArgs());
        long expireSeconds = annotation.expire();
        long waitMillis = annotation.waitTime();
        boolean enableWatchdog = annotation.watchdog();

        // 尝试获取锁
        String requestId;
        if (waitMillis > 0) {
            requestId = distributedLock.tryLock(lockKey, expireSeconds, waitMillis);
        } else {
            requestId = distributedLock.lock(lockKey, expireSeconds);
        }

        if (requestId == null) {
            LOGGER.warn("获取分布式锁失败, key={}, waitTime={}ms", lockKey, waitMillis);
            throw new RuntimeException("获取分布式锁失败，请稍后重试");
        }

        LOGGER.debug("获取分布式锁成功, key={}, requestId={}", lockKey, requestId);

        // 启动看门狗
        if (enableWatchdog) {
            distributedLock.renewLock(lockKey, requestId, expireSeconds);
        }

        try {
            return joinPoint.proceed();
        } finally {
            // 停止看门狗并释放锁
            distributedLock.unlock(lockKey, requestId);
            LOGGER.debug("释放分布式锁, key={}, requestId={}", lockKey, requestId);
        }
    }

    /**
     * 解析SpEL表达式，将 #paramName 替换为实际参数值
     * 支持格式如: "order:#id"、"user:#user.name"
     */
    private String parseSpelKey(String keyExpression, Method method, Object[] args) {
        // 如果不包含SpEL表达式，直接返回
        if (!keyExpression.contains("#")) {
            return keyExpression;
        }

        // 构建SpEL上下文
        EvaluationContext context = new StandardEvaluationContext();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        // 将 "order:#id" 格式拆分为前缀和SpEL部分
        StringBuilder result = new StringBuilder();
        String[] parts = keyExpression.split("#");
        result.append(parts[0]);

        for (int i = 1; i < parts.length; i++) {
            // 提取SpEL变量名（取到非字母数字下划线点号的字符为止）
            String part = parts[i];
            int end = 0;
            while (end < part.length() &&
                    (Character.isLetterOrDigit(part.charAt(end)) || part.charAt(end) == '_' || part.charAt(end) == '.')) {
                end++;
            }
            String spelExpr = part.substring(0, end);
            String remaining = part.substring(end);

            // 解析SpEL表达式
            Expression expression = spelParser.parseExpression(spelExpr);
            Object value = expression.getValue(context);
            result.append(value != null ? value.toString() : "null");
            result.append(remaining);
        }

        return result.toString();
    }
}
