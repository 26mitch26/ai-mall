package com.ai.mall.agent.ops.service.audit;

import com.ai.mall.agent.ops.model.GateRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门控审批单仓库：审批单是"有人签字的记录"，必须可查、可批、重启不丢。
 */
class GateDecisionStoreTest {

    @TempDir
    Path tempDir;

    private GateDecisionStore store;

    @BeforeEach
    void setUp() {
        store = newStore(50);
    }

    private GateDecisionStore newStore(int maxRetained) {
        return new GateDecisionStore(new ObjectMapper(),
                tempDir.resolve("gates.json").toString(), maxRetained);
    }

    private GateRecord record(String id, String status) {
        return GateRecord.builder().id(id).alertId("alert-" + id).healActionId("heal-" + id)
                .playbook("rollback").riskScore(0.42).approver("oncall-engineer").status(status)
                .reason("risk=0.42").createdAt(LocalDateTime.now()).build();
    }

    @Test
    void pendingListsOnlyUndecidedRecords() {
        store.save(record("g1", "pending_approval"));
        store.save(record("g2", "approved"));
        store.save(record("g3", "pending_approval"));

        List<GateRecord> pending = store.pending();
        assertEquals(2, pending.size());
        assertTrue(pending.stream().allMatch(r -> "pending_approval".equals(r.getStatus())));
    }

    @Test
    void decideUpdatesStatusAndApprover() {
        store.save(record("g1", "pending_approval"));

        GateRecord decided = store.decide("g1", "approved", "oncall", "推演通过");

        assertNotNull(decided);
        assertEquals("approved", decided.getStatus());
        assertEquals("oncall", decided.getDecidedBy());
        assertEquals("推演通过", decided.getExecutionSummary());
        assertNotNull(decided.getDecidedAt());
        assertTrue(store.pending().isEmpty(), "审批后不应再出现在待审批队列");
    }

    @Test
    void recordsSurviveRestart() {
        store.save(record("g1", "pending_approval"));

        GateDecisionStore restarted = newStore(50);
        restarted.load();

        assertEquals(1, restarted.findAll().size());
        assertEquals("rollback", restarted.find("g1").orElseThrow().getPlaybook());
    }

    @Test
    void retentionEvictsOldestRecords() {
        GateDecisionStore small = newStore(2);
        small.save(record("g1", "approved"));
        small.save(record("g2", "approved"));
        small.save(record("g3", "approved"));

        assertEquals(2, small.count());
        assertTrue(small.find("g1").isEmpty(), "超出保留数时应淘汰最旧审批单");
    }

    @Test
    void summaryCountsByStatus() {
        store.save(record("g1", "pending_approval"));
        store.save(record("g2", "approved"));

        var summary = store.summary();
        assertEquals(2, ((Number) summary.get("total")).intValue());
        assertEquals(1, ((Number) summary.get("pending")).intValue());
    }
}
