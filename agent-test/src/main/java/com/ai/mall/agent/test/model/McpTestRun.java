package com.ai.mall.agent.test.model;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次测试任务的运行句柄（MCP 工具层的异步返回体）。
 *
 * <p>为什么需要它：MCP 的 {@code tools/call} 必须单次 POST 单次响应，而一轮契约回归
 * 在开启 LLM 扩写或命中客服会话套件时可达数十秒到上百秒。同步返回会让 Agent 客户端
 * 超时或阻塞，因此 {@code run_tests} 默认立即返回句柄，由调用方轮询 {@code get_run_status}。
 * 这同时补上了 {@code TestAgent} 里"声明了并发结构却没有实现调度"的空缺。
 */
@Data
@RequiredArgsConstructor
public class McpTestRun {

    public enum Status {
        /** 已入队或执行中 */
        RUNNING,
        /** 跑完并产出报告（报告内可能仍有失败用例） */
        SUCCEEDED,
        /** 调度层失败：线程池拒绝、任务抛异常等 */
        FAILED
    }

    private final String runId;
    private final String module;
    private final String caller;
    private final LocalDateTime startedAt;

    private volatile Status status = Status.RUNNING;
    private volatile LocalDateTime finishedAt;
    private volatile String reportId;
    private volatile Integer totalTests;
    private volatile Integer failedTests;
    private volatile String error;

    public boolean isFinished() {
        return status != Status.RUNNING;
    }
}
