package com.ai.mall.agent.test.service.mcp;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.McpTestRun;
import com.ai.mall.agent.test.model.TestReport;
import com.ai.mall.agent.test.service.agent.TestAgent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 测试任务的调度与运行句柄登记处。
 *
 * <p>{@code TestAgent#runTests} 是同步阻塞的，串行 for 循环逐条打真实服务。
 * 这里用有界线程池 + 有界队列把它包成"可并发调度 + 可拒绝"的形态：
 * <ul>
 *   <li>并发上限默认 1——回归会真实打 8081/8087，放开并发等于把测试变成压测；</li>
 *   <li>队列满则立即拒绝，让调用方明确知道"系统忙"，而不是无声堆积；</li>
 *   <li>运行记录有上限，防止长驻进程内存无限增长。</li>
 * </ul>
 */
@Slf4j
@Service
public class McpRunRegistry {

    private final TestAgent testAgent;
    private final AgentTestConfig config;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<String, McpTestRun> runs = new ConcurrentHashMap<>();

    public McpRunRegistry(TestAgent testAgent, AgentTestConfig config) {
        this.testAgent = testAgent;
        this.config = config;
        int threads = Math.max(1, config.getMcp().getMaxConcurrentRuns());
        int queueSize = Math.max(1, config.getMcp().getMaxQueuedRuns());
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize), new ThreadPoolExecutor.AbortPolicy());
        this.executor.setThreadFactory(runnable -> {
            Thread thread = new Thread(runnable, "mcp-test-run");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 提交一轮测试任务并立即返回句柄。
     *
     * @param module 已通过白名单校验的模块名
     * @param caller 调用方标识（审计用）
     * @throws McpRunRejectedException 队列已满
     */
    public McpTestRun submit(String module, String caller) {
        McpTestRun run = new McpTestRun(UUID.randomUUID().toString(), module, caller, LocalDateTime.now());
        runs.put(run.getRunId(), run);
        try {
            executor.execute(() -> execute(run));
        } catch (RejectedExecutionException ex) {
            runs.remove(run.getRunId());
            throw new McpRunRejectedException("测试任务队列已满（并发 "
                    + config.getMcp().getMaxConcurrentRuns() + "，排队 "
                    + config.getMcp().getMaxQueuedRuns() + "），请稍后重试");
        }
        evictOldRuns();
        log.info("[mcp] run submitted: runId={} module={} caller={}", run.getRunId(), module, caller);
        return run;
    }

    private void execute(McpTestRun run) {
        try {
            TestReport report = testAgent.runTests(run.getModule());
            synchronized (run) {
                if (report == null) {
                    run.setStatus(McpTestRun.Status.FAILED);
                    run.setError("测试执行未产出报告");
                } else {
                    run.setReportId(report.getId());
                    run.setTotalTests(report.getTotalTests());
                    run.setFailedTests(report.getFailedTests());
                    run.setStatus(McpTestRun.Status.SUCCEEDED);
                }
                run.setFinishedAt(LocalDateTime.now());
                run.notifyAll();
            }
            log.info("[mcp] run finished: runId={} module={} status={} reportId={}",
                    run.getRunId(), run.getModule(), run.getStatus(), run.getReportId());
        } catch (Throwable ex) {
            synchronized (run) {
                run.setStatus(McpTestRun.Status.FAILED);
                run.setError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                run.setFinishedAt(LocalDateTime.now());
                run.notifyAll();
            }
            log.error("[mcp] run crashed: runId={} module={}", run.getRunId(), run.getModule(), ex);
        }
    }

    public McpTestRun find(String runId) {
        return runId == null ? null : runs.get(runId);
    }

    /**
     * 等待运行结束，最多 {@code maxSeconds} 秒。
     *
     * @return true 表示已在等待窗口内完成
     */
    public boolean awaitCompletion(McpTestRun run, int maxSeconds) {
        if (run == null || run.isFinished() || maxSeconds <= 0) {
            return run != null && run.isFinished();
        }
        long deadline = System.nanoTime() + maxSeconds * 1_000_000_000L;
        synchronized (run) {
            while (run.getStatus() == McpTestRun.Status.RUNNING) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    return false;
                }
                try {
                    run.wait(Math.min(remainingMs, 500L));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /** 最近完成的运行记录，按启动时间倒序。 */
    public List<Map<String, Object>> recentRuns(int limit) {
        int capped = Math.max(1, Math.min(limit, 50));
        return runs.values().stream()
                .sorted(Comparator.comparing(McpTestRun::getStartedAt).reversed())
                .limit(capped)
                .map(run -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("runId", run.getRunId());
                    item.put("module", run.getModule());
                    item.put("caller", run.getCaller());
                    item.put("status", run.getStatus().name());
                    item.put("reportId", run.getReportId());
                    item.put("totalTests", run.getTotalTests());
                    item.put("failedTests", run.getFailedTests());
                    item.put("startedAt", String.valueOf(run.getStartedAt()));
                    item.put("finishedAt", run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
                    return item;
                })
                .toList();
    }

    private void evictOldRuns() {
        int max = Math.max(1, config.getMcp().getRunHistorySize());
        if (runs.size() <= max) {
            return;
        }
        int toRemove = runs.size() - max;
        for (McpTestRun run : runs.values().stream()
                .filter(candidate -> !candidate.isFinished())
                .sorted(Comparator.comparing(McpTestRun::getStartedAt))
                .toList()) {
            if (toRemove <= 0) {
                break;
            }
            if (runs.remove(run.getRunId()) != null) {
                toRemove--;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
