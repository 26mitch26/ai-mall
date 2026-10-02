package com.ai.mall.agent.ops.service.knowledge;

import com.ai.mall.agent.ops.service.knowledge.KnowledgeGraphService.CauseCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 知识图谱服务单元测试（内存降级路径）。
 *
 * <p>Neo4j 不可用时服务会降级到内存图，根因候选查询必须在该路径下同样可用，
 * 否则生产环境一旦连不上图数据库，整条 RCA 链路就会退化。
 *
 * <p>本测试强制进入降级分支，验证：
 * <ul>
 *   <li>服务自身的故障模式能被召回</li>
 *   <li>沿 DEPENDS_ON 反向回溯上游服务，其故障模式也能被召回（图遍历生效）</li>
 *   <li>同名故障模式取最大先验而非累加，避免扭曲概率分布</li>
 * </ul>
 */
class KnowledgeGraphServiceTest {

    private KnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        // Mockito 默认对未打桩的方法返回 null，因此连通性探测
        // neo4jClient.query("RETURN 1").fetch().one() 会在解引用时抛 NPE，
        // 被 initServiceTopology 的 catch 捕获后进入内存降级分支。
        // 这里刻意不使用 deep stub：链式深层打桩在本项目的 Eclipse 编译器下会解析失败。
        Neo4jClient client = mock(Neo4jClient.class);

        service = new KnowledgeGraphService(client);
        service.initServiceTopology();
    }

    @Test
    @DisplayName("应召回服务自身的故障模式")
    void shouldReturnOwnFailureModes() {
        List<CauseCandidate> candidates = service.findCandidateCauses("redis-cluster");

        assertFalse(candidates.isEmpty(), "redis-cluster 应有故障模式候选");
        assertTrue(containsCause(candidates, "cache_bloat"),
                "redis-cluster 应易感 cache_bloat，实际=" + candidates);
    }

    @Test
    @DisplayName("应沿依赖链回溯上游服务，召回其故障模式")
    void shouldTraverseUpstreamServices() {
        // 拓扑：api-gateway -> order-service，故 order-service 的上游是 api-gateway
        List<CauseCandidate> orderCauses = service.findCandidateCauses("order-service");

        assertTrue(containsCause(orderCauses, "deployment_regression"),
                "应包含 order-service 自身的故障模式，实际=" + orderCauses);
        assertTrue(containsCause(orderCauses, "firewall_misconfig"),
                "应包含上游 api-gateway 的故障模式，证明图遍历生效，实际=" + orderCauses);
    }

    @Test
    @DisplayName("同名故障模式在多条路径出现时取最大先验，不累加")
    void shouldTakeMaxPriorForSharedFailureModes() {
        List<CauseCandidate> candidates = service.findCandidateCauses("order-service");

        // order-service 的 traffic_spike 先验 0.20，上游 api-gateway 为 0.25，应取 0.25 而非 0.45
        double trafficSpikePrior = priorOf(candidates, "traffic_spike");
        assertEquals(0.25, trafficSpikePrior, 1e-9,
                "同名故障模式应取最大先验，实际=" + trafficSpikePrior);
    }

    @Test
    @DisplayName("候选应按先验降序排列，便于下游优先考察高概率根因")
    void shouldSortCandidatesByPriorDescending() {
        List<CauseCandidate> candidates = service.findCandidateCauses("order-service");

        for (int i = 1; i < candidates.size(); i++) {
            assertTrue(candidates.get(i - 1).prior() >= candidates.get(i).prior(),
                    "候选应按先验降序，实际=" + candidates);
        }
    }

    @Test
    @DisplayName("故障模式名称应与RCAAgent条件概率表的key口径一致")
    void failureModesShouldAlignWithCptKeys() {
        // RCAAgent.CPT 覆盖的故障模式全集
        List<String> cptKeys = List.of(
                "deployment_regression", "traffic_spike", "resource_contention", "gc_overhead",
                "memory_leak", "cache_bloat", "log_bloat", "data_growth", "disk_failure",
                "dns_failure", "connection_pool_exhaustion", "bandwidth_saturation",
                "firewall_misconfig");

        List<CauseCandidate> all = service.findCandidateCauses("order-service");
        for (CauseCandidate candidate : all) {
            assertTrue(cptKeys.contains(candidate.name()),
                    "图谱故障模式 '" + candidate.name() + "' 未出现在 CPT 中，"
                            + "会导致似然取默认值、推理退化");
        }
    }

    private boolean containsCause(List<CauseCandidate> candidates, String name) {
        return candidates.stream().anyMatch(c -> c.name().equals(name));
    }

    private double priorOf(List<CauseCandidate> candidates, String name) {
        return candidates.stream()
                .filter(c -> c.name().equals(name))
                .map(CauseCandidate::prior)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到故障模式: " + name + "，实际=" + candidates));
    }
}
