package com.ai.mall.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redis分布式锁实现
 * 支持原子加锁/解锁、可重入锁、自动续期（看门狗机制）
 * Created by macro on 2024/1/1.
 */
public class DistributedLock {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedLock.class);

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 解锁Lua脚本：只有持锁者才能解锁，保证原子性
     * KEYS[1] = 锁key, ARGV[1] = requestId
     * 如果锁的值等于requestId，则删除锁，否则返回0
     */
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    /**
     * 可重入加锁Lua脚本
     * KEYS[1] = 锁key, ARGV[1] = requestId, ARGV[2] = 过期时间(秒)
     * 如果锁不存在则创建，如果锁的持有者是当前请求则重入计数+1
     */
    private static final String REENTRANT_LOCK_SCRIPT =
            "local key = KEYS[1] " +
            "local requestId = ARGV[1] " +
            "local expireTime = tonumber(ARGV[2]) " +
            "local current = redis.call('get', key) " +
            "if current == false then " +
            "    redis.call('set', key, requestId, 'NX', 'EX', expireTime) " +
            "    redis.call('hset', key .. ':reentrant', requestId, '1') " +
            "    redis.call('expire', key .. ':reentrant', expireTime) " +
            "    return 1 " +
            "elseif current == requestId then " +
            "    local count = redis.call('hincrby', key .. ':reentrant', requestId, 1) " +
            "    redis.call('expire', key, expireTime) " +
            "    redis.call('expire', key .. ':reentrant', expireTime) " +
            "    return count " +
            "else " +
            "    return 0 " +
            "end";

    /**
     * 可重入解锁Lua脚本
     * KEYS[1] = 锁key, ARGV[1] = requestId
     * 重入计数-1，如果计数为0则删除锁
     */
    private static final String REENTRANT_UNLOCK_SCRIPT =
            "local key = KEYS[1] " +
            "local requestId = ARGV[1] " +
            "local current = redis.call('get', key) " +
            "if current == requestId then " +
            "    local count = redis.call('hincrby', key .. ':reentrant', requestId, -1) " +
            "    if count <= 0 then " +
            "        redis.call('del', key) " +
            "        redis.call('del', key .. ':reentrant') " +
            "    end " +
            "    return 1 " +
            "else " +
            "    return 0 " +
            "end";

    /**
     * 续期Lua脚本：只有持锁者才能续期
     */
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('expire', KEYS[1], tonumber(ARGV[2])) " +
            "else " +
            "    return 0 " +
            "end";

    /** 锁key前缀 */
    private static final String LOCK_PREFIX = "distributed:lock:";

    /** 看门狗续期间隔（默认为过期时间的1/3） */
    private static final long RENEW_INTERVAL_RATIO = 3;

    /** 看门狗调度器 */
    private final ScheduledExecutorService watchdogExecutor = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "distributed-lock-watchdog");
                t.setDaemon(true);
                return t;
            }
    );

    /** 看门狗任务映射表 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> watchdogTasks = new ConcurrentHashMap<>();

    public DistributedLock(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 加锁，使用SET NX EX原子操作
     *
     * @param key           锁的key
     * @param expireSeconds 过期时间(秒)
     * @return requestId 持锁者标识，解锁时需要传入；加锁失败返回null
     */
    public String lock(String key, long expireSeconds) {
        String requestId = UUID.randomUUID().toString();
        String lockKey = LOCK_PREFIX + key;
        Boolean result = redisTemplate.opsForValue().setIfAbsent(lockKey, requestId, expireSeconds, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(result)) {
            LOGGER.debug("加锁成功, key={}, requestId={}", lockKey, requestId);
            return requestId;
        }
        LOGGER.debug("加锁失败, key={}", lockKey);
        return null;
    }

    /**
     * 解锁，使用Lua脚本保证原子性（只有持锁者才能解锁）
     *
     * @param key       锁的key
     * @param requestId 持锁者标识
     * @return 是否解锁成功
     */
    public boolean unlock(String key, String requestId) {
        String lockKey = LOCK_PREFIX + key;
        // 停止看门狗
        stopWatchdog(lockKey);
        RedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(lockKey),
                requestId);
        boolean success = Long.valueOf(1L).equals(result);
        if (success) {
            LOGGER.debug("解锁成功, key={}, requestId={}", lockKey, requestId);
        } else {
            LOGGER.warn("解锁失败(非持锁者或锁已过期), key={}, requestId={}", lockKey, requestId);
        }
        return success;
    }

    /**
     * 尝试加锁，带超时等待
     *
     * @param key           锁的key
     * @param expireSeconds 过期时间(秒)
     * @param waitMillis    最大等待时间(毫秒)
     * @return requestId 持锁者标识；超时未获取到锁返回null
     */
    public String tryLock(String key, long expireSeconds, long waitMillis) {
        long startTime = System.currentTimeMillis();
        long retryInterval = 50; // 重试间隔50ms
        while (true) {
            String requestId = lock(key, expireSeconds);
            if (requestId != null) {
                return requestId;
            }
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= waitMillis) {
                LOGGER.debug("尝试加锁超时, key={}, waitMillis={}", LOCK_PREFIX + key, waitMillis);
                return null;
            }
            try {
                Thread.sleep(Math.min(retryInterval, waitMillis - elapsed));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("尝试加锁被中断, key={}", LOCK_PREFIX + key);
                return null;
            }
        }
    }

    /**
     * 可重入锁，支持同一线程多次获取同一把锁
     *
     * @param key           锁的key
     * @param requestId     持锁者标识（通常为线程ID或UUID）
     * @param expireSeconds 过期时间(秒)
     * @return 是否加锁成功
     */
    public boolean lockReentrant(String key, String requestId, long expireSeconds) {
        String lockKey = LOCK_PREFIX + key;
        RedisScript<Long> script = new DefaultRedisScript<>(REENTRANT_LOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(lockKey),
                requestId,
                String.valueOf(expireSeconds));
        boolean success = result != null && result > 0;
        if (success) {
            LOGGER.debug("可重入加锁成功, key={}, requestId={}, count={}", lockKey, requestId, result);
        } else {
            LOGGER.debug("可重入加锁失败, key={}, requestId={}", lockKey, requestId);
        }
        return success;
    }

    /**
     * 可重入解锁
     *
     * @param key       锁的key
     * @param requestId 持锁者标识
     * @return 是否解锁成功
     */
    public boolean unlockReentrant(String key, String requestId) {
        String lockKey = LOCK_PREFIX + key;
        RedisScript<Long> script = new DefaultRedisScript<>(REENTRANT_UNLOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(lockKey),
                requestId);
        boolean success = Long.valueOf(1L).equals(result);
        if (success) {
            LOGGER.debug("可重入解锁成功, key={}, requestId={}", lockKey, requestId);
        } else {
            LOGGER.warn("可重入解锁失败, key={}, requestId={}", lockKey, requestId);
        }
        return success;
    }

    /**
     * 自动续期（看门狗机制）
     * 启动后台定时任务，定期刷新锁的过期时间
     *
     * @param key           锁的key
     * @param requestId     持锁者标识
     * @param expireSeconds 原始过期时间(秒)，续期时使用相同时间
     */
    public void renewLock(String key, String requestId, long expireSeconds) {
        String lockKey = LOCK_PREFIX + key;
        long renewInterval = expireSeconds * 1000 / RENEW_INTERVAL_RATIO;
        ScheduledFuture<?> future = watchdogExecutor.scheduleAtFixedRate(() -> {
            try {
                RedisScript<Long> script = new DefaultRedisScript<>(RENEW_SCRIPT, Long.class);
                Long result = redisTemplate.execute(script,
                        Collections.singletonList(lockKey),
                        requestId,
                        String.valueOf(expireSeconds));
                if (Long.valueOf(1L).equals(result)) {
                    LOGGER.debug("看门狗续期成功, key={}, requestId={}", lockKey, requestId);
                } else {
                    LOGGER.warn("看门狗续期失败(锁已不属于当前请求), key={}, requestId={}", lockKey, requestId);
                    stopWatchdog(lockKey);
                }
            } catch (Exception e) {
                LOGGER.error("看门狗续期异常, key={}, requestId={}", lockKey, requestId, e);
                stopWatchdog(lockKey);
            }
        }, renewInterval, renewInterval, TimeUnit.MILLISECONDS);
        watchdogTasks.put(lockKey, future);
        LOGGER.debug("看门狗启动, key={}, requestId={}, renewInterval={}ms", lockKey, requestId, renewInterval);
    }

    /**
     * 停止看门狗
     */
    private void stopWatchdog(String lockKey) {
        ScheduledFuture<?> future = watchdogTasks.remove(lockKey);
        if (future != null) {
            future.cancel(false);
            LOGGER.debug("看门狗停止, key={}", lockKey);
        }
    }

    /**
     * 生成当前线程的唯一requestId
     */
    public static String currentRequestId() {
        return UUID.randomUUID().toString() + ":" + Thread.currentThread().getId();
    }
}
