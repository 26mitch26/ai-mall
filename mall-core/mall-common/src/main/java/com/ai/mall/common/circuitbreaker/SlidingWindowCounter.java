package com.ai.mall.common.circuitbreaker;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 滑动窗口失败率计数器：统计最近时间窗口内的请求总数与失败数。
 * 生产熔断的触发条件通常是"窗口内失败率超阈值"而非"连续 N 次失败"——
 * 后者对夹在成功之间的零星故障完全免疫，窗口失败率能把非连续但高频的故障兜住。
 */
final class SlidingWindowCounter {

    private final Deque<long[]> events = new ArrayDeque<>();
    private final long windowMs;

    SlidingWindowCounter(long windowMs) {
        this.windowMs = windowMs;
    }

    record Snapshot(long total, long errors) {
        double errorRate() {
            return total == 0 ? 0.0 : (double) errors / total;
        }
    }

    void recordSuccess(long now) {
        record(now, false);
    }

    void recordFailure(long now) {
        record(now, true);
    }

    private void record(long now, boolean failed) {
        synchronized (events) {
            events.addLast(new long[]{now, failed ? 1 : 0});
        }
    }

    /** 取窗口快照：先惰性清理窗口外事件再统计 */
    Snapshot snapshot(long now) {
        synchronized (events) {
            long cutoff = now - windowMs;
            while (!events.isEmpty() && events.peekFirst()[0] < cutoff) {
                events.pollFirst();
            }
            long total = 0;
            long errors = 0;
            for (long[] event : events) {
                total++;
                if (event[1] == 1) {
                    errors++;
                }
            }
            return new Snapshot(total, errors);
        }
    }

    /** 清空历史：熔断恢复 CLOSED 时调用，让恢复后的统计从零开始 */
    void reset() {
        synchronized (events) {
            events.clear();
        }
    }
}
