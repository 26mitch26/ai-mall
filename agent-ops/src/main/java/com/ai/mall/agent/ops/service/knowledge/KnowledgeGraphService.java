package com.ai.mall.agent.ops.service.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class KnowledgeGraphService {

    private final Map<String, Set<String>> dependencies = new HashMap<>();
    private final Map<String, Map<String, Object>> serviceInfo = new HashMap<>();

    public KnowledgeGraphService() {
        initServiceTopology();
    }

    private void initServiceTopology() {
        addService("order-service", Map.of("tier", "core", "namespace", "production"));
        addService("payment-service", Map.of("tier", "core", "namespace", "production"));
        addService("inventory-service", Map.of("tier", "core", "namespace", "production"));
        addService("user-service", Map.of("tier", "support", "namespace", "production"));
        addService("api-gateway", Map.of("tier", "gateway", "namespace", "production"));
        addService("mysql-primary", Map.of("tier", "database", "namespace", "production"));
        addService("redis-cluster", Map.of("tier", "cache", "namespace", "production"));
        addService("elasticsearch", Map.of("tier", "search", "namespace", "production"));

        addDependency("order-service", "payment-service");
        addDependency("order-service", "inventory-service");
        addDependency("order-service", "user-service");
        addDependency("payment-service", "mysql-primary");
        addDependency("inventory-service", "mysql-primary");
        addDependency("user-service", "redis-cluster");
        addDependency("api-gateway", "order-service");
    }

    public void addService(String name, Map<String, Object> info) {
        serviceInfo.put(name, info);
        dependencies.putIfAbsent(name, new HashSet<>());
    }

    public void addDependency(String from, String to) {
        dependencies.computeIfAbsent(from, k -> new HashSet<>()).add(to);
    }

    public List<String> getDependencies(String service) {
        return new ArrayList<>(dependencies.getOrDefault(service, Collections.emptySet()));
    }

    public List<String> getDependents(String service) {
        List<String> dependents = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            if (entry.getValue().contains(service)) {
                dependents.add(entry.getKey());
            }
        }
        return dependents;
    }

    public List<List<String>> findImpactPaths(String service) {
        List<List<String>> paths = new ArrayList<>();
        findPathsBFS(service, paths);
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

    public Map<String, Object> getServiceInfo(String service) {
        return serviceInfo.getOrDefault(service, Collections.emptyMap());
    }

    public double calculateImpactScore(String service) {
        List<List<String>> paths = findImpactPaths(service);
        int totalPaths = paths.size();
        int maxDepth = paths.stream().mapToInt(List::size).max().orElse(1);
        return Math.min(1.0, (totalPaths * 0.1) + (maxDepth * 0.05));
    }
}
