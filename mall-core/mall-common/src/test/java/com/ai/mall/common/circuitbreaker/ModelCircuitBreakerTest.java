package com.ai.mall.common.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelCircuitBreaker 状态机单元测试。
 * 覆盖六条状态转移路径 + 滑动窗口失败率触发逻辑。
 * 通过注入可编程时间源，状态转移测试不依赖真实 sleep，确定性且毫秒级完成。
 */
class ModelCircuitBreakerTest {

    private AtomicLong now;
    private ModelCircuitBreaker breaker;

    /** 固定参数：连续失败 3 次熔断、冷却 100ms、探测 2 次 */
    private static final int FAILURE_THRESHOLD = 3;
    private static final long RESET_TIMEOUT_MS = 100;
    private static final int HALF_OPEN_MAX_ATTEMPTS = 2;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(0L);
        breaker = new ModelCircuitBreaker(
                FAILURE_THRESHOLD, RESET_TIMEOUT_MS, HALF_OPEN_MAX_ATTEMPTS);
        breaker.setTimeSource(now::get);
    }

    private void advancePastResetTimeout() {
        now.addAndGet(RESET_TIMEOUT_MS + 1);
    }

    private void driveToOpen() {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            breaker.recordFailure();
        }
        assertEquals(ModelCircuitBreaker.CircuitState.OPEN, breaker.getState());
    }

    private void driveToHalfOpen() {
        driveToOpen();
        advancePastResetTimeout();
        assertTrue(breaker.isAvailable(), "冷却结束后应放行探测并转入 HALF_OPEN");
        assertEquals(ModelCircuitBreaker.CircuitState.HALF_OPEN, breaker.getState());
    }

    @Test
    @DisplayName("CLOSED → OPEN：连续失败达到阈值即熔断")
    void closedToOpenAfterThresholdConsecutiveFailures() {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            breaker.recordFailure();
        }
        assertEquals(ModelCircuitBreaker.CircuitState.OPEN, breaker.getState());
        assertFalse(breaker.isAvailable(), "OPEN 状态应拒绝请求");
    }

    @Test
    @DisplayName("成功打断连续失败计数：不足阈值不熔断")
    void successResetsConsecutiveFailureCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(ModelCircuitBreaker.CircuitState.CLOSED, breaker.getState(),
                "连续失败被成功打断后（最多连续 2 次），不应熔断");
        assertTrue(breaker.isAvailable());
    }

    @Test
    @DisplayName("OPEN → HALF_OPEN：冷却超时后自动放行探测")
    void openTransitionsToHalfOpenAfterResetTimeout() {
        driveToOpen();
        assertFalse(breaker.isAvailable(), "冷却未到时仍拒绝");
        assertEquals(ModelCircuitBreaker.CircuitState.OPEN, breaker.getState());
        advancePastResetTimeout();
        assertTrue(breaker.isAvailable(), "冷却到期后放行并转 HALF_OPEN");
        assertEquals(ModelCircuitBreaker.CircuitState.HALF_OPEN, breaker.getState());
    }

    @Test
    @DisplayName("HALF_OPEN → CLOSED：探测成功达到阈值恢复")
    void halfOpenRecoversToClosedAfterSuccesses() {
        driveToHalfOpen();
        for (int i = 0; i < HALF_OPEN_MAX_ATTEMPTS; i++) {
            breaker.recordSuccess();
        }
        assertEquals(ModelCircuitBreaker.CircuitState.CLOSED, breaker.getState());
        assertTrue(breaker.isAvailable());
    }

    @Test
    @DisplayName("HALF_OPEN → OPEN：任一探测失败立即重新熔断")
    void halfOpenFailsBackToOpenOnProbeFailure() {
        driveToHalfOpen();
        breaker.recordFailure();
        assertEquals(ModelCircuitBreaker.CircuitState.OPEN, breaker.getState(),
                "半开探测失败应立即回 OPEN 重新冷却");
        assertFalse(breaker.isAvailable());
    }

    @Test
    @DisplayName("HALF_OPEN → OPEN：探测次数用尽仍无成功，防卡死回归")
    void halfOpenExhaustsAttemptsAndFallsBackToOpen() {
        driveToHalfOpen();
        for (int i = 0; i < HALF_OPEN_MAX_ATTEMPTS; i++) {
            assertTrue(breaker.isAvailable(), "配额内应放行探测");
        }
        assertFalse(breaker.isAvailable(), "配额用尽后应拒绝");
        assertEquals(ModelCircuitBreaker.CircuitState.OPEN, breaker.getState(),
                "探测配额用尽应回退 OPEN，而非卡死在 HALF_OPEN");
    }

    @Test
    @DisplayName("reset() 应回到 CLOSED 并清空统计")
    void resetReturnsToClosedAndClearsState() {
        driveToOpen();
        breaker.reset();
        assertEquals(ModelCircuitBreaker.CircuitState.CLOSED, breaker.getState());
        assertTrue(breaker.isAvailable());
        assertEquals(0, breaker.getFailureCount());
    }

    // ======================== 滑动窗口失败率触发 ========================

    /** 隔离窗口触发：连续失败阈值设超大，只让窗口失败率一路生效 */
    private ModelCircuitBreaker windowOnlyBreaker(long windowMs, double threshold, int minRequests) {
        ModelCircuitBreaker b = new ModelCircuitBreaker(
                1_000_000, RESET_TIMEOUT_MS, HALF_OPEN_MAX_ATTEMPTS,
                windowMs, threshold, minRequests);
        b.setTimeSource(now::get);
        return b;
    }

    @Test
    @DisplayName("窗口失败率熔断：夹在成功中的高频失败（非连续）也能触发")
    void windowErrorRateTriggersOpenWithoutConsecutiveFailures() {
        ModelCircuitBreaker wb = windowOnlyBreaker(10_000, 0.5, 10);
        // 12 次请求 8 次失败 = 66.7% > 50%，但连续失败最多 2 次（远低于大阈值）
        boolean[] failures = {true, false, true, false, true, true,
                              false, true, true, false, true, true};
        for (boolean isFailure : failures) {
            if (isFailure) wb.recordFailure(); else wb.recordSuccess();
        }
        assertEquals(ModelCircuitBreaker.CircuitState.OPEN, wb.getState(),
                "窗口失败率 66.7% 超阈值应熔断，尽管不存在连续失败");
    }

    @Test
    @DisplayName("冷启动保护：样本不足时即使 100% 失败也不熔断")
    void windowColdStartShouldNotOpenBelowMinimumRequests() {
        ModelCircuitBreaker wb = windowOnlyBreaker(10_000, 0.5, 10);
        // 仅 4 个请求（< minimumRequests=10），全部失败
        for (int i = 0; i < 4; i++) {
            wb.recordFailure();
        }
        assertEquals(ModelCircuitBreaker.CircuitState.CLOSED, wb.getState(),
                "冷启动样本不足时不允许按失败率熔断");
    }

    @Test
    @DisplayName("成功稀释失败率：错误率未达阈值不熔断")
    void successShouldDiluteErrorRate() {
        ModelCircuitBreaker wb = windowOnlyBreaker(10_000, 0.5, 10);
        // 4 失败 + 7 成功：样本达 minRequests=10 时错误率为 4/10=40% < 50%，
        // 此后成功继续稀释（4/11=36%），全程不应触发
        for (int i = 0; i < 4; i++) {
            wb.recordFailure();
        }
        for (int i = 0; i < 7; i++) {
            wb.recordSuccess();
        }
        assertEquals(ModelCircuitBreaker.CircuitState.CLOSED, wb.getState(),
                "错误率被大量成功稀释后不应熔断");
    }

    @Test
    @DisplayName("窗口滑动：过期失败随时间滚出窗口，不再参与统计")
    void expiredFailuresShouldRollOutOfWindow() {
        ModelCircuitBreaker wb = windowOnlyBreaker(1_000, 0.5, 5);
        // t=0 时 5 个请求 4 个失败（80%），窗口内累计超阈值
        for (int i = 0; i < 4; i++) {
            wb.recordFailure();
        }
        wb.recordSuccess();
        assertEquals(ModelCircuitBreaker.CircuitState.OPEN, wb.getState(),
                "窗口内失败率 80% 应熔断");

        // 熔断后推进到新窗口（时间越过 1s 窗口），此时新失败应基于空窗口重新统计
        wb.reset();
        now.addAndGet(2_000);
        // 新窗口：连续大阈值不触发，4 个失败也 < minimumRequests=5
        for (int i = 0; i < 4; i++) {
            wb.recordFailure();
        }
        assertEquals(ModelCircuitBreaker.CircuitState.CLOSED, wb.getState(),
                "过期失败已滚出窗口，重新统计后不应因历史数据误断");
    }

    @Test
    @DisplayName("恢复 CLOSED 后窗口统计清零，避免旧错误率立即触发二次熔断")
    void closedRecoveryShouldResetWindowStatistics() {
        ModelCircuitBreaker wb = windowOnlyBreaker(10_000, 0.5, 10);
        for (int i = 0; i < 8; i++) {
            wb.recordFailure();
        }
        wb.recordSuccess();
        wb.recordSuccess();
        assertEquals(ModelCircuitBreaker.CircuitState.OPEN, wb.getState());
        wb.reset();
        assertEquals(ModelCircuitBreaker.CircuitState.CLOSED, wb.getState());

        // reset 后窗口应被清空：仅 5 个成功 + 1 个失败，total=6 < minRequests
        for (int i = 0; i < 5; i++) {
            wb.recordSuccess();
        }
        wb.recordFailure();
        assertEquals(ModelCircuitBreaker.CircuitState.CLOSED, wb.getState(),
                "旧窗口失败率不应在 reset 后继续累积生效");
    }
}

