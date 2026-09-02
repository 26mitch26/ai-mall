package com.ai.mall.common.common.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 三态熔断器：支持 CLOSED / OPEN / HALF_OPEN 状态切换
 * 用于模型路由场景，当远程模型连续失败时自动熔断，降级到本地模型
 */
public class ModelCircuitBreaker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCircuitBreaker.class);

    /**
     * 熔断器状态枚举
     */
    public enum CircuitState {
        /** 关闭状态：正常放行请求 */
        CLOSED,
        /** 打开状态：拒绝请求，直接降级 */
        OPEN,
        /** 半开状态：允许少量探测请求通过 */
        HALF_OPEN
    }

    /** 当前状态 */
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);

    /** 连续失败计数 */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /** 连续成功计数（HALF_OPEN状态下使用） */
    private final AtomicInteger successCount = new AtomicInteger(0);

    /** 半开状态下已使用的探测次数 */
    private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);

    /** 连续失败阈值，达到此值触发 CLOSED→OPEN */
    private final int failureThreshold;

    /** OPEN 状态持续时间（毫秒），超时后进入 HALF_OPEN */
    private final long resetTimeoutMs;

    /** HALF_OPEN 状态下最大探测次数 */
    private final int halfOpenMaxAttempts;

    /** 进入 OPEN 状态的时间戳 */
    private volatile long openedAt = 0;

    /** 保证状态切换的原子性 */
    private final ReentrantLock stateLock = new ReentrantLock();

    public ModelCircuitBreaker() {
        this(5, 30_000, 3);
    }

    public ModelCircuitBreaker(int failureThreshold, long resetTimeoutMs, int halfOpenMaxAttempts) {
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
        this.halfOpenMaxAttempts = halfOpenMaxAttempts;
    }

    /**
     * 记录成功调用
     */
    public void recordSuccess() {
        stateLock.lock();
        try {
            CircuitState current = state.get();
            if (current == CircuitState.CLOSED) {
                failureCount.set(0);
            } else if (current == CircuitState.HALF_OPEN) {
                int count = successCount.incrementAndGet();
                if (count >= halfOpenMaxAttempts) {
                    transitionTo(CircuitState.CLOSED);
                    LOGGER.info("熔断器探测成功次数达到阈值({})，状态切换: HALF_OPEN → CLOSED", count);
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 记录失败调用
     */
    public void recordFailure() {
        stateLock.lock();
        try {
            CircuitState current = state.get();
            if (current == CircuitState.CLOSED) {
                int count = failureCount.incrementAndGet();
                if (count >= failureThreshold) {
                    transitionTo(CircuitState.OPEN);
                    LOGGER.warn("连续失败次数达到阈值({})，状态切换: CLOSED → OPEN", count);
                }
            } else if (current == CircuitState.HALF_OPEN) {
                transitionTo(CircuitState.OPEN);
                LOGGER.warn("半开状态探测失败，状态切换: HALF_OPEN → OPEN");
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 检查熔断器是否可用（允许请求通过）
     */
    public boolean isAvailable() {
        CircuitState current = getState();
        switch (current) {
            case CLOSED:
                return true;
            case OPEN:
                // 检查是否超过重置超时时间，如果是则尝试切换到半开状态
                if (System.currentTimeMillis() - openedAt >= resetTimeoutMs) {
                    stateLock.lock();
                    try {
                        // 双重检查，防止并发问题
                        if (state.get() == CircuitState.OPEN
                                && System.currentTimeMillis() - openedAt >= resetTimeoutMs) {
                            transitionTo(CircuitState.HALF_OPEN);
                            LOGGER.info("OPEN状态超时，状态切换: OPEN → HALF_OPEN，开始探测");
                        }
                    } finally {
                        stateLock.unlock();
                    }
                    return true;
                }
                return false;
            case HALF_OPEN:
                // 半开状态下限制并发探测数量
                int attempts = halfOpenAttempts.incrementAndGet();
                if (attempts <= halfOpenMaxAttempts) {
                    return true;
                }
                // 修正 AI 初版遗漏的恢复路径：探测次数用尽仍未恢复时，
                // 必须退回 OPEN 并重置探测计数，否则状态会卡死在 HALF_OPEN，
                // 后续所有探测请求均被拒绝、熔断器再也无法恢复。
                stateLock.lock();
                try {
                    if (state.get() == CircuitState.HALF_OPEN) {
                        transitionTo(CircuitState.OPEN);
                        LOGGER.warn("HALF_OPEN 探测次数用尽({})，状态回退: HALF_OPEN → OPEN", attempts);
                    }
                } finally {
                    stateLock.unlock();
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * 获取当前熔断器状态
     */
    public CircuitState getState() {
        return state.get();
    }

    /**
     * 状态切换
     */
    private void transitionTo(CircuitState newState) {
        CircuitState oldState = state.getAndSet(newState);
        if (oldState != newState) {
            if (newState == CircuitState.OPEN) {
                openedAt = System.currentTimeMillis();
            } else if (newState == CircuitState.HALF_OPEN) {
                successCount.set(0);
                halfOpenAttempts.set(0);
            } else if (newState == CircuitState.CLOSED) {
                failureCount.set(0);
                successCount.set(0);
                halfOpenAttempts.set(0);
            }
        }
    }

    /**
     * 重置熔断器到初始状态
     */
    public void reset() {
        stateLock.lock();
        try {
            transitionTo(CircuitState.CLOSED);
            LOGGER.info("熔断器已重置为 CLOSED 状态");
        } finally {
            stateLock.unlock();
        }
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public long getResetTimeoutMs() {
        return resetTimeoutMs;
    }

    public int getHalfOpenMaxAttempts() {
        return halfOpenMaxAttempts;
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public int getSuccessCount() {
        return successCount.get();
    }
}
