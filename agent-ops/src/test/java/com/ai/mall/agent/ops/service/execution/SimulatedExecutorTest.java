package com.ai.mall.agent.ops.service.execution;

import com.ai.mall.agent.ops.model.GateRecord;
import com.ai.mall.agent.ops.service.agent.HealAgent;
import com.ai.mall.agent.ops.service.event.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 模拟执行器：把"批准后处置"做成可观察、可复现的推演。
 *
 * <p>关键约束：不得声称做了真实变更。测试同时守住两条边界——
 * 匹配不到步骤时必须判失败（而不是"成功"），disabled 模式必须真的不推演。
 */
class SimulatedExecutorTest {

    private HealAgent healAgent;
    private SimulatedExecutor executor;

    @BeforeEach
    void setUp() {
        healAgent = new HealAgent(mock(EventBus.class));
        executor = new SimulatedExecutor(healAgent, "simulated");
    }

    private GateRecord gate(String playbook) {
        return GateRecord.builder().id("g1").alertId("a1").playbook(playbook)
                .riskScore(0.5).createdAt(LocalDateTime.now()).build();
    }

    @Test
    void stepsOfIsNotEmptyForKnownPlaybook() {
        // 此前 steps 只出现在 dryRun 的日志里，模拟执行器拿不到步骤内容
        assertFalse(healAgent.stepsOf("rollback").isEmpty());
        assertFalse(healAgent.verificationOf("rollback").isBlank());
        assertTrue(healAgent.playbookNames().contains("rollback"));
    }

    @Test
    void simulatedExecutionCoversEveryStep() {
        var record = executor.execute(gate("rollback"));

        assertEquals("simulated", record.getMode());
        assertTrue(record.isSuccess());
        assertEquals(healAgent.stepsOf("rollback").size(), record.getSteps().size());
        assertTrue(record.getSteps().stream().allMatch(step -> "SIMULATED_OK".equals(step.getSimulatedOutcome())));
        assertTrue(record.getVerification().contains("未真实执行"),
                "验证结论必须明确标注未真实执行，不能伪装成已验证");
        assertTrue(record.getTotalCostMs() > 0);
    }

    @Test
    void executionIsReproducible() {
        long first = executor.execute(gate("restart")).getTotalCostMs();
        long second = executor.execute(gate("restart")).getTotalCostMs();
        assertEquals(first, second, "推演耗时应可复现，便于对比");
    }

    @Test
    void unknownPlaybookIsTreatedAsFailureNotSuccess() {
        var record = executor.execute(gate("no-such-playbook"));

        assertFalse(record.isSuccess(), "匹配不到步骤定义时不能报成功");
        assertTrue(record.getVerification().contains("未匹配"));
    }

    @Test
    void disabledModeSkipsExecution() {
        SimulatedExecutor disabled = new SimulatedExecutor(healAgent, "disabled");
        assertFalse(disabled.enabled());

        var record = disabled.execute(gate("rollback"));
        assertEquals("disabled", record.getMode());
        assertFalse(record.isSuccess());
        assertTrue(record.getSteps().isEmpty());
    }

    @Test
    void historyKeepsLatestExecutions() {
        executor.execute(gate("rollback"));
        executor.execute(gate("restart"));

        List<?> history = executor.recentExecutions();
        assertNotNull(history);
        assertEquals(2, history.size());
    }
}
