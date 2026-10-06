package com.ai.mall.agent.test.service.insight;

import com.ai.mall.agent.test.model.KnownDefect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInsightStoreTest {

    private Path tempFile;

    private TestInsightStore newStore() throws IOException {
        tempFile = Files.createTempFile("insights", ".json");
        return new TestInsightStore(new ObjectMapper(), tempFile.toString());
    }

    @AfterEach
    void cleanup() throws IOException {
        if (tempFile != null && Files.exists(tempFile)) {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void recordShouldAggregateByApiKey() throws IOException {
        TestInsightStore store = newStore();
        store.record("GET", "/api/orders", "业务码异常");
        store.record("GET", "/api/orders", "业务码异常");
        store.record("POST", "/api/orders", "5xx");

        List<KnownDefect> snapshot = store.snapshot();
        assertEquals(2, snapshot.size());

        KnownDefect orders = store.findByApi("GET", "/api/orders");
        assertNotNull(orders);
        assertEquals(2, orders.getOccurrences(), "同接口重复失败应累计次数");
        assertTrue(orders.isHitThisRun());
        assertNotNull(orders.getFirstSeen());
    }

    @Test
    void hitThisRunShouldResetAfterRoundCompletion() throws IOException {
        TestInsightStore store = newStore();
        store.record("GET", "/api/orders", "业务码异常");
        store.markRoundCompleted();
        assertFalse(store.findByApi("GET", "/api/orders").isHitThisRun());
    }

    @Test
    void recordShouldPersistAndReload() throws IOException {
        TestInsightStore first = newStore();
        first.record("GET", "/api/products", "Schema 违规");

        TestInsightStore second = new TestInsightStore(new ObjectMapper(), tempFile.toString());
        second.load();

        KnownDefect defect = second.findByApi("GET", "/api/products");
        assertNotNull(defect, "经验应跨会话持久化");
        assertEquals(1, defect.getOccurrences());
    }

    @Test
    void snapshotShouldBeDetachedFromStore() throws IOException {
        TestInsightStore store = newStore();
        store.record("GET", "/api/orders", "业务码异常");

        List<KnownDefect> snapshot = store.snapshot();
        store.markRoundCompleted();

        assertTrue(snapshot.get(0).isHitThisRun(),
                "快照必须是副本，否则清轮次标记会反向污染已落库的报告");
        assertFalse(store.findByApi("GET", "/api/orders").isHitThisRun(),
                "库内对象本身仍应被清标记");
    }

    @Test
    void clearShouldRemoveAllAndPersist() throws IOException {
        TestInsightStore store = newStore();
        store.record("GET", "/api/orders", "业务码异常");
        store.clear();
        assertTrue(store.snapshot().isEmpty());

        TestInsightStore reloaded = new TestInsightStore(new ObjectMapper(), tempFile.toString());
        reloaded.load();
        assertTrue(reloaded.snapshot().isEmpty(), "清空应同样落盘");
    }
}
