package com.ai.mall.agent.ops.service.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * 知识图谱服务：基于Neo4j Cypher查询的服务拓扑与故障传播关系管理
 *
 * 核心能力：
 * - 服务拓扑节点与依赖关系的Cypher CRUD
 * - 基于图遍历的影响路径查询（findImpactPaths）
 * - 基于图遍历的上游候选根因定位（findUpstreamCauses）
 * - 基于图结构的影响分数计算（calculateImpactScore）
 *
 * 降级策略：当Neo4j不可用时，自动降级到内存HashMap存储
 */
@Slf4j
@Service
public class KnowledgeGraphService {

    private final Neo4jClient neo4jClient;

    /** 降级标志：Neo4j不可用时为true，所有操作回退到内存存储 */
    private volatile boolean fallbackToInMemory = false;

    // ========== 内存降级存储 ==========
    private final Map<String, Set<String>> dependencies = new HashMap<>();
    private final Map<String, Map<String, Object>> serviceInfo = new HashMap<>();

    /** 内存降级：服务 -> (故障模式 -> 先验概率) */
    private final Map<String, Map<String, Double>> serviceFailureModes = new HashMap<>();

    /**
     * 服务易感的故障模式及其先验概率 P(FailureMode | Service)。
     *
     * <p>这是图谱中连接"服务拓扑"与"故障根因"的关键一层：没有它，根因分析只能拿到
     * 服务名（如 mysql-primary），而无法与故障类型（如 connection_pool_exhaustion）
     * 的条件概率表对齐，贝叶斯推理会退化成概率均分。
     *
     * <p>每个服务的先验之和为 1.0，构成该服务上的完整故障分布。
     */
    private static final Map<String, Map<String, Double>> FAILURE_MODE_PRIORS = Map.of(
            "api-gateway", Map.of(
                    "traffic_spike", 0.25,
                    "dns_failure", 0.20,
                    "connection_pool_exhaustion", 0.20,
                    "bandwidth_saturation", 0.20,
                    "firewall_misconfig", 0.15),
            "order-service", Map.of(
                    "deployment_regression", 0.30,
                    "resource_contention", 0.25,
                    "traffic_spike", 0.20,
                    "gc_overhead", 0.15,
                    "connection_pool_exhaustion", 0.10),
            "payment-service", Map.of(
                    "deployment_regression", 0.30,
                    "connection_pool_exhaustion", 0.25,
                    "resource_contention", 0.20,
                    "gc_overhead", 0.15,
                    "data_growth", 0.10),
            "inventory-service", Map.of(
                    "deployment_regression", 0.25,
                    "resource_contention", 0.25,
                    "data_growth", 0.20,
                    "gc_overhead", 0.20,
                    "log_bloat", 0.10),
            "user-service", Map.of(
                    "cache_bloat", 0.35,
                    "memory_leak", 0.25,
                    "deployment_regression", 0.20,
                    "connection_pool_exhaustion", 0.20),
            "mysql-primary", Map.of(
                    "data_growth", 0.30,
                    "connection_pool_exhaustion", 0.25,
                    "disk_failure", 0.20,
                    "resource_contention", 0.15,
                    "log_bloat", 0.10),
            "redis-cluster", Map.of(
                    "cache_bloat", 0.40,
                    "memory_leak", 0.30,
                    "connection_pool_exhaustion", 0.20,
                    "resource_contention", 0.10),
            "elasticsearch", Map.of(
                    "disk_failure", 0.25,
                    "data_growth", 0.25,
                    "memory_leak", 0.25,
                    "log_bloat", 0.15,
                    "gc_overhead", 0.10)
    );

    public KnowledgeGraphService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 根因候选：故障模式名称 + 来自知识图谱的先验概率。
     *
     * @param name  故障模式名称，与 RCAAgent 条件概率表(CPT)的 key 对齐
     * @param prior 先验概率 P(FailureMode | Service)，由图谱关系属性携带
     */
    public record CauseCandidate(String name, double prior) {
    }

    /**
     * 初始化服务拓扑数据到Neo4j
     * 先检测Neo4j连通性，连通则通过Cypher写入拓扑，否则降级到内存
     */
    @PostConstruct
    public void initServiceTopology() {
        try {
            // 检测Neo4j连通性
            neo4jClient.query("RETURN 1").fetch().one();
            log.info("Neo4j connection verified, initializing service topology via Cypher");

            // 清除旧数据，幂等初始化
            neo4jClient.query("MATCH (n) DETACH DELETE n").run();

            // 创建服务节点
            addService("order-service", Map.of("tier", "core", "namespace", "production"));
            addService("payment-service", Map.of("tier", "core", "namespace", "production"));
            addService("inventory-service", Map.of("tier", "core", "namespace", "production"));
            addService("user-service", Map.of("tier", "support", "namespace", "production"));
            addService("api-gateway", Map.of("tier", "gateway", "namespace", "production"));
            addService("mysql-primary", Map.of("tier", "database", "namespace", "production"));
            addService("redis-cluster", Map.of("tier", "cache", "namespace", "production"));
            addService("elasticsearch", Map.of("tier", "search", "namespace", "production"));

            // 创建依赖关系
            addDependency("order-service", "payment-service");
            addDependency("order-service", "inventory-service");
            addDependency("order-service", "user-service");
            addDependency("payment-service", "mysql-primary");
            addDependency("inventory-service", "mysql-primary");
            addDependency("user-service", "redis-cluster");
            addDependency("api-gateway", "order-service");

            initFailureModes();

            log.info("Service topology initialized in Neo4j successfully");
        } catch (Exception e) {
            fallbackToInMemory = true;
            log.warn("Neo4j unavailable, falling back to in-memory storage: {}", e.getMessage());
            initInMemoryTopology();
        }
    }

    /**
     * 初始化故障模式节点与「服务 -[HAS_FAILURE_MODE]-> 故障模式」关系。
     *
     * <p>先验概率作为关系属性存入图谱，使根因分析的贝叶斯先验来自真实图数据，
     * 而非代码内硬编码的常量表。
     */
    private void initFailureModes() {
        for (Map.Entry<String, Map<String, Double>> entry : FAILURE_MODE_PRIORS.entrySet()) {
            String service = entry.getKey();
            for (Map.Entry<String, Double> mode : entry.getValue().entrySet()) {
                addFailureMode(service, mode.getKey(), mode.getValue());
            }
        }
        log.info("Failure modes initialized in Neo4j: {} services", FAILURE_MODE_PRIORS.size());
    }

    /** 内存降级初始化 */
    private void initInMemoryTopology() {
        addServiceInMemory("order-service", Map.of("tier", "core", "namespace", "production"));
        addServiceInMemory("payment-service", Map.of("tier", "core", "namespace", "production"));
        addServiceInMemory("inventory-service", Map.of("tier", "core", "namespace", "production"));
        addServiceInMemory("user-service", Map.of("tier", "support", "namespace", "production"));
        addServiceInMemory("api-gateway", Map.of("tier", "gateway", "namespace", "production"));
        addServiceInMemory("mysql-primary", Map.of("tier", "database", "namespace", "production"));
        addServiceInMemory("redis-cluster", Map.of("tier", "cache", "namespace", "production"));
        addServiceInMemory("elasticsearch", Map.of("tier", "search", "namespace", "production"));

        addDependencyInMemory("order-service", "payment-service");
        addDependencyInMemory("order-service", "inventory-service");
        addDependencyInMemory("order-service", "user-service");
        addDependencyInMemory("payment-service", "mysql-primary");
        addDependencyInMemory("inventory-service", "mysql-primary");
        addDependencyInMemory("user-service", "redis-cluster");
        addDependencyInMemory("api-gateway", "order-service");

        for (Map.Entry<String, Map<String, Double>> entry : FAILURE_MODE_PRIORS.entrySet()) {
            serviceFailureModes.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
    }

    // ==================== 公共API（方法签名不变） ====================

    /**
     * 添加服务节点
     * Cypher: CREATE (s:Service {name: $name, tier: $tier, namespace: $namespace})
     */
    public void addService(String name, Map<String, Object> info) {
        if (fallbackToInMemory) {
            addServiceInMemory(name, info);
            return;
        }
        try {
            neo4jClient.query("""
                    CREATE (s:Service {
                        name: $name,
                        tier: $tier,
                        namespace: $namespace
                    })
                    """)
                    .bind(name).to("name")
                    .bind(info.getOrDefault("tier", "unknown")).to("tier")
                    .bind(info.getOrDefault("namespace", "default")).to("namespace")
                    .run();
            log.debug("Created service node in Neo4j: {}", name);
        } catch (Exception e) {
            log.warn("Failed to add service '{}' to Neo4j, falling back to in-memory: {}", name, e.getMessage());
            fallbackToInMemory = true;
            addServiceInMemory(name, info);
        }
    }

    /**
     * 添加依赖关系
     * Cypher: MATCH (a:Service), (b:Service) WHERE a.name=$from AND b.name=$to CREATE (a)-[:DEPENDS_ON]->(b)
     */
    public void addDependency(String from, String to) {
        if (fallbackToInMemory) {
            addDependencyInMemory(from, to);
            return;
        }
        try {
            neo4jClient.query("""
                    MATCH (a:Service), (b:Service)
                    WHERE a.name = $from AND b.name = $to
                    CREATE (a)-[:DEPENDS_ON]->(b)
                    """)
                    .bind(from).to("from")
                    .bind(to).to("to")
                    .run();
            log.debug("Created dependency in Neo4j: {} -> {}", from, to);
        } catch (Exception e) {
            log.warn("Failed to add dependency '{} -> {}' to Neo4j, falling back to in-memory: {}", from, to, e.getMessage());
            fallbackToInMemory = true;
            addDependencyInMemory(from, to);
        }
    }

    /**
     * 建立「服务 -[HAS_FAILURE_MODE {prior}]-> 故障模式」关系
     * Cypher: MATCH (s:Service {name:$svc}), (f:FailureMode {name:$mode})
     *         MERGE (s)-[r:HAS_FAILURE_MODE]->(f) SET r.prior = $prior
     */
    public void addFailureMode(String service, String failureMode, double prior) {
        if (fallbackToInMemory) {
            serviceFailureModes
                    .computeIfAbsent(service, k -> new HashMap<>())
                    .put(failureMode, prior);
            return;
        }
        try {
            neo4jClient.query("""
                    MERGE (f:FailureMode {name: $mode})
                    WITH f
                    MATCH (s:Service {name: $svc})
                    MERGE (s)-[r:HAS_FAILURE_MODE]->(f)
                    SET r.prior = $prior
                    """)
                    .bind(service).to("svc")
                    .bind(failureMode).to("mode")
                    .bind(prior).to("prior")
                    .run();
            log.debug("Linked failure mode '{}' to service '{}' with prior {}", failureMode, service, prior);
        } catch (Exception e) {
            log.warn("Failed to link failure mode '{}' to '{}', falling back in-memory: {}",
                    failureMode, service, e.getMessage());
            fallbackToInMemory = true;
            serviceFailureModes
                    .computeIfAbsent(service, k -> new HashMap<>())
                    .put(failureMode, prior);
        }
    }

    /**
     * 查询根因候选：沿服务依赖链回溯上游服务，收集其易感的故障模式及先验概率。
     *
     * <p>返回的是<b>故障模式</b>而非服务名，与 RCAAgent 条件概率表(CPT)的 key 对齐，
     * 贝叶斯推理的似然与先验才能真正相乘。
     *
     * Cypher: MATCH (up:Service)-[:DEPENDS_ON*0..3]->(s:Service {name: $name})
     *         MATCH (up)-[r:HAS_FAILURE_MODE]->(f:FailureMode)
     *         RETURN DISTINCT f.name AS name, r.prior AS prior ORDER BY prior DESC
     *
     * @param service 告警所在服务
     * @return 候选故障模式（含图上先验），图谱无数据时返回空列表
     */
    public List<CauseCandidate> findCandidateCauses(String service) {
        if (fallbackToInMemory) {
            return findCandidateCausesInMemory(service);
        }
        try {
            return neo4jClient.query("""
                    MATCH (up:Service)-[:DEPENDS_ON*0..3]->(s:Service {name: $name})
                    MATCH (up)-[r:HAS_FAILURE_MODE]->(f:FailureMode)
                    RETURN DISTINCT f.name AS name, r.prior AS prior
                    ORDER BY prior DESC
                    """)
                    .bind(service).to("name")
                    .fetchAs(CauseCandidate.class)
                    .mappedBy((type, record) -> new CauseCandidate(
                            record.get("name").asString(),
                            record.get("prior").asDouble()))
                    .all()
                    .stream()
                    .toList();
        } catch (Exception e) {
            log.warn("Neo4j query failed for findCandidateCauses('{}'), falling back: {}",
                    service, e.getMessage());
            fallbackToInMemory = true;
            return findCandidateCausesInMemory(service);
        }
    }

    /** 内存降级的根因候选查询：BFS 回溯上游服务，汇总其故障模式先验 */
    private List<CauseCandidate> findCandidateCausesInMemory(String service) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(service);
        visited.add(service);

        // 沿 DEPENDS_ON 反向回溯最多 3 跳
        for (int depth = 0; depth < 3 && !queue.isEmpty(); depth++) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                    if (entry.getValue().contains(current) && visited.add(entry.getKey())) {
                        queue.add(entry.getKey());
                    }
                }
            }
        }

        Map<String, Double> merged = new LinkedHashMap<>();
        for (String svc : visited) {
            for (Map.Entry<String, Double> mode : serviceFailureModes
                    .getOrDefault(svc, Collections.emptyMap()).entrySet()) {
                // 同名故障模式取最大先验，避免重复累加扭曲分布
                merged.merge(mode.getKey(), mode.getValue(), Double::max);
            }
        }

        return merged.entrySet().stream()
                .map(e -> new CauseCandidate(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingDouble(CauseCandidate::prior).reversed())
                .toList();
    }

    /**
     * 获取服务的下游依赖
     * Cypher: MATCH (s:Service {name: $name})-[:DEPENDS_ON]->(d:Service) RETURN d.name
     */
    public List<String> getDependencies(String service) {
        if (fallbackToInMemory) {
            return new ArrayList<>(dependencies.getOrDefault(service, Collections.emptySet()));
        }
        try {
            return neo4jClient.query("""
                    MATCH (s:Service {name: $name})-[:DEPENDS_ON]->(d:Service)
                    RETURN d.name AS name
                    """)
                    .bind(service).to("name")
                    .fetchAs(String.class)
                    .mappedBy((type, record) -> record.get("name").asString())
                    .all()
                    .stream()
                    .toList();
        } catch (Exception e) {
            log.warn("Neo4j query failed for getDependencies('{}'), falling back: {}", service, e.getMessage());
            fallbackToInMemory = true;
            return new ArrayList<>(dependencies.getOrDefault(service, Collections.emptySet()));
        }
    }

    /**
     * 获取服务的上游依赖方（谁依赖了该服务）
     * Cypher: MATCH (s:Service)<-[:DEPENDS_ON]-(d:Service) WHERE s.name=$name RETURN d.name
     */
    public List<String> getDependents(String service) {
        if (fallbackToInMemory) {
            List<String> dependents = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                if (entry.getValue().contains(service)) {
                    dependents.add(entry.getKey());
                }
            }
            return dependents;
        }
        try {
            return neo4jClient.query("""
                    MATCH (s:Service)<-[:DEPENDS_ON]-(d:Service)
                    WHERE s.name = $name
                    RETURN d.name AS name
                    """)
                    .bind(service).to("name")
                    .fetchAs(String.class)
                    .mappedBy((type, record) -> record.get("name").asString())
                    .all()
                    .stream()
                    .toList();
        } catch (Exception e) {
            log.warn("Neo4j query failed for getDependents('{}'), falling back: {}", service, e.getMessage());
            fallbackToInMemory = true;
            List<String> dependents = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                if (entry.getValue().contains(service)) {
                    dependents.add(entry.getKey());
                }
            }
            return dependents;
        }
    }

    /**
     * 查询从指定服务出发的所有影响路径（深度1~5）
     * Cypher: MATCH path=(s:Service {name: $name})-[:DEPENDS_ON*1..5]->(d:Service)
     *         RETURN [n in nodes(path) | n.name]
     */
    public List<List<String>> findImpactPaths(String service) {
        if (fallbackToInMemory) {
            return findImpactPathsInMemory(service);
        }
        try {
            @SuppressWarnings("unchecked")
            Collection<List<String>> paths = (Collection<List<String>>) (Collection<?>) neo4jClient.query("""
                    MATCH path=(s:Service {name: $name})-[:DEPENDS_ON*1..5]->(d:Service)
                    RETURN [n IN nodes(path) | n.name] AS pathNames
                    """)
                    .bind(service).to("name")
                    .fetchAs(List.class)
                    .mappedBy((type, record) -> {
                        org.neo4j.driver.Value pathValue = record.get("pathNames");
                        List<String> names = new ArrayList<>();
                        for (int i = 0; i < pathValue.size(); i++) {
                            names.add(pathValue.get(i).asString());
                        }
                        return names;
                    })
                    .all();

            List<List<String>> result = new ArrayList<>(paths);

            // 如果没有下游路径，返回仅包含自身的单节点路径
            if (result.isEmpty()) {
                result.add(Collections.singletonList(service));
            }

            return result;
        } catch (Exception e) {
            log.warn("Neo4j query failed for findImpactPaths('{}'), falling back: {}", service, e.getMessage());
            fallbackToInMemory = true;
            return findImpactPathsInMemory(service);
        }
    }

    /**
     * 查询上游候选根因节点（递归查找所有上游依赖服务）
     * Cypher: MATCH path=(d:Service)-[:DEPENDS_ON*1..5]->(s:Service {name: $name})
     *         RETURN DISTINCT d.name
     */
    public List<String> findUpstreamCauses(String service) {
        if (fallbackToInMemory) {
            return findUpstreamCausesInMemory(service);
        }
        try {
            Collection<String> causes = neo4jClient.query("""
                    MATCH path=(d:Service)-[:DEPENDS_ON*1..5]->(s:Service {name: $name})
                    RETURN DISTINCT d.name AS name
                    """)
                    .bind(service).to("name")
                    .fetchAs(String.class)
                    .mappedBy((type, record) -> record.get("name").asString())
                    .all();

            return new ArrayList<>(causes);
        } catch (Exception e) {
            log.warn("Neo4j query failed for findUpstreamCauses('{}'), falling back: {}", service, e.getMessage());
            fallbackToInMemory = true;
            return findUpstreamCausesInMemory(service);
        }
    }

    /**
     * 获取服务信息
     * Cypher: MATCH (s:Service {name: $name}) RETURN s.tier AS tier, s.namespace AS namespace
     */
    public Map<String, Object> getServiceInfo(String service) {
        if (fallbackToInMemory) {
            return serviceInfo.getOrDefault(service, Collections.emptyMap());
        }
        try {
            Optional<Map<String, Object>> result = neo4jClient.query("""
                    MATCH (s:Service {name: $name})
                    RETURN s.tier AS tier, s.namespace AS namespace
                    """)
                    .bind(service).to("name")
                    .fetch()
                    .one()
                    .map(record -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("tier", record.get("tier") != null ? record.get("tier").toString() : "unknown");
                        info.put("namespace", record.get("namespace") != null ? record.get("namespace").toString() : "default");
                        return info;
                    });
            return result.orElse(Collections.emptyMap());
        } catch (Exception e) {
            log.warn("Neo4j query failed for getServiceInfo('{}'), falling back: {}", service, e.getMessage());
            fallbackToInMemory = true;
            return serviceInfo.getOrDefault(service, Collections.emptyMap());
        }
    }

    /**
     * 基于图遍历计算影响分数
     * 影响分数 = f(下游路径数量, 最大影响深度, 上游依赖方数量)
     * 路径越多、深度越深、被依赖越多 → 影响分数越高
     */
    public double calculateImpactScore(String service) {
        if (fallbackToInMemory) {
            return calculateImpactScoreInMemory(service);
        }
        try {
            // 查询下游影响路径数量和最大深度
            Optional<Map<String, Object>> stats = neo4jClient.query("""
                    MATCH path=(s:Service {name: $name})-[:DEPENDS_ON*1..5]->(d:Service)
                    RETURN count(path) AS pathCount, max(length(path)) AS maxDepth
                    """)
                    .bind(service).to("name")
                    .fetch()
                    .one()
                    .map(record -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("pathCount", record.get("pathCount") != null ? ((Number) record.get("pathCount")).longValue() : 0L);
                        map.put("maxDepth", record.get("maxDepth") != null ? ((Number) record.get("maxDepth")).longValue() : 0L);
                        return map;
                    });

            long pathCount = stats.map(m -> (long) m.get("pathCount")).orElse(0L);
            long maxDepth = stats.map(m -> (long) m.get("maxDepth")).orElse(0L);

            // 查询上游依赖方数量（被多少服务依赖）
            long dependentCount = neo4jClient.query("""
                    MATCH (s:Service)<-[:DEPENDS_ON]-(d:Service)
                    WHERE s.name = $name
                    RETURN count(d) AS cnt
                    """)
                    .bind(service).to("name")
                    .fetchAs(Long.class)
                    .mappedBy((type, record) -> ((Number) record.get("cnt")).longValue())
                    .one()
                    .orElse(0L);

            // 影响分数计算：路径权重 + 深度权重 + 被依赖权重
            double score = (pathCount * 0.1) + (maxDepth * 0.05) + (dependentCount * 0.15);
            return Math.min(1.0, score);
        } catch (Exception e) {
            log.warn("Neo4j query failed for calculateImpactScore('{}'), falling back: {}", service, e.getMessage());
            fallbackToInMemory = true;
            return calculateImpactScoreInMemory(service);
        }
    }

    // ==================== 内存降级实现 ====================

    private void addServiceInMemory(String name, Map<String, Object> info) {
        serviceInfo.put(name, info);
        dependencies.putIfAbsent(name, new HashSet<>());
    }

    private void addDependencyInMemory(String from, String to) {
        dependencies.computeIfAbsent(from, k -> new HashSet<>()).add(to);
    }

    private List<List<String>> findImpactPathsInMemory(String start) {
        List<List<String>> paths = new ArrayList<>();
        findPathsBFS(start, paths);
        if (paths.isEmpty()) {
            paths.add(Collections.singletonList(start));
        }
        return paths;
    }

    private void findPathsBFS(String start, List<List<String>> paths) {
        Queue<List<String>> queue = new LinkedList<>();
        queue.add(Arrays.asList(start));

        while (!queue.isEmpty()) {
            List<String> path = queue.poll();
            String current = path.get(path.size() - 1);

            Set<String> deps = dependencies.getOrDefault(current, Collections.emptySet());
            if (deps.isEmpty()) {
                paths.add(path);
            } else {
                for (String dep : deps) {
                    if (!path.contains(dep)) {
                        List<String> newPath = new ArrayList<>(path);
                        newPath.add(dep);
                        queue.add(newPath);
                    }
                }
            }
        }
    }

    private List<String> findUpstreamCausesInMemory(String service) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        // 找到所有直接或间接依赖该服务的上游节点
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            if (entry.getValue().contains(service)) {
                queue.add(entry.getKey());
                visited.add(entry.getKey());
            }
        }
        // 递归向上查找
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                if (entry.getValue().contains(current) && !visited.contains(entry.getKey())) {
                    visited.add(entry.getKey());
                    queue.add(entry.getKey());
                }
            }
        }
        return new ArrayList<>(visited);
    }

    private double calculateImpactScoreInMemory(String service) {
        List<List<String>> paths = findImpactPathsInMemory(service);
        int totalPaths = paths.size();
        int maxDepth = paths.stream().mapToInt(List::size).max().orElse(1);
        int dependentCount = getDependents(service).size();
        double score = (totalPaths * 0.1) + (maxDepth * 0.05) + (dependentCount * 0.15);
        return Math.min(1.0, score);
    }
}
