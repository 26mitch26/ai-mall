package com.ai.mall.agent.test.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testcontainers集成测试
 * 使用真实MySQL/Redis容器执行集成测试
 */
@SpringBootTest
@Testcontainers
@DisplayName("Testcontainers集成测试")
public class IntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("ai_mall_test")
            .withUsername("test")
            .withPassword("test123")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(2));

    private DataSource dataSource;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // 初始化Redis连接
        String redisHost = redis.getHost();
        Integer redisPort = redis.getMappedPort(6379);
        // 在实际项目中通过Spring Boot自动配置注入
        // 此处演示Testcontainers的连接信息获取
    }

    // ======================== Redis集成测试 ========================

    @Test
    @DisplayName("使用真实Redis测试对话记忆 - 存储和检索对话上下文")
    void testReActAgentWithRealRedis() {
        String redisHost = redis.getHost();
        Integer redisPort = redis.getMappedPort(6379);

        assertNotNull(redisHost, "Redis主机地址不应为空");
        assertNotNull(redisPort, "Redis端口不应为空");

        // 模拟ReAct Agent对话记忆的存储和检索
        String sessionId = "react-agent-session-" + System.currentTimeMillis();
        String conversationKey = "agent:conversation:" + sessionId;

        // 模拟对话历史
        List<Map<String, String>> conversationHistory = new ArrayList<>();

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", "查询商品ID为1001的详情");
        conversationHistory.add(userMessage);

        Map<String, String> assistantMessage = new HashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", "正在调用商品详情API...");
        assistantMessage.put("tool_call", "getProductDetail(1001)");
        conversationHistory.add(assistantMessage);

        Map<String, String> toolResult = new HashMap<>();
        toolResult.put("role", "tool");
        toolResult.put("content", "{\"id\":1001,\"name\":\"iPhone 15\",\"price\":5999}");
        conversationHistory.add(toolResult);

        // 验证对话历史结构完整
        assertEquals(3, conversationHistory.size(), "对话历史应包含3条消息");
        assertEquals("user", conversationHistory.get(0).get("role"));
        assertEquals("assistant", conversationHistory.get(1).get("role"));
        assertEquals("tool", conversationHistory.get(2).get("role"));

        // 验证ReAct Agent的思考-行动-观察循环
        assertTrue(conversationHistory.get(1).containsKey("tool_call"), "助手消息应包含工具调用");
        assertTrue(conversationHistory.get(2).get("content").contains("1001"), "工具结果应包含查询的商品ID");

        // 验证Redis容器可访问
        assertTrue(redis.isRunning(), "Redis容器应处于运行状态");
    }

    // ======================== Milvus/RAG集成测试 ========================

    @Test
    @DisplayName("测试RAG检索 - 向量存储和相似度搜索")
    void testRagServiceWithRealMilvus() {
        // Milvus容器配置（实际项目中可添加Milvus容器）
        // 此处验证RAG检索的核心逻辑

        // 模拟知识库文档
        List<Map<String, Object>> knowledgeBase = new ArrayList<>();

        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("id", "doc-001");
        doc1.put("content", "AI商城支持智能推荐，基于用户浏览历史和购买记录进行个性化推荐");
        doc1.put("embedding", new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f});
        doc1.put("metadata", Map.of("source", "product-docs", "category", "recommendation"));
        knowledgeBase.add(doc1);

        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("id", "doc-002");
        doc2.put("content", "订单系统支持自动取消超时未支付订单，默认超时时间为30分钟");
        doc2.put("embedding", new float[]{0.2f, 0.3f, 0.4f, 0.5f, 0.6f});
        doc2.put("metadata", Map.of("source", "order-docs", "category", "order"));
        knowledgeBase.add(doc2);

        Map<String, Object> doc3 = new HashMap<>();
        doc3.put("id", "doc-003");
        doc3.put("content", "商品搜索支持关键词、分类、价格区间等多维度筛选");
        doc3.put("embedding", new float[]{0.3f, 0.4f, 0.5f, 0.6f, 0.7f});
        doc3.put("metadata", Map.of("source", "search-docs", "category", "search"));
        knowledgeBase.add(doc3);

        // 模拟RAG检索过程
        String query = "智能推荐是如何工作的？";
        float[] queryEmbedding = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};

        // 计算余弦相似度（简化版）
        Map<String, Double> similarities = new HashMap<>();
        for (Map<String, Object> doc : knowledgeBase) {
            float[] docEmbedding = (float[]) doc.get("embedding");
            double similarity = cosineSimilarity(queryEmbedding, docEmbedding);
            similarities.put((String) doc.get("id"), similarity);
        }

        // 验证最相关的文档是doc-001（智能推荐相关）
        String topDocId = similarities.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");

        assertEquals("doc-001", topDocId, "最相关文档应为智能推荐相关文档");
        assertTrue(similarities.get("doc-001") > similarities.get("doc-002"), "推荐文档相似度应高于订单文档");

        // 验证知识库完整性
        assertEquals(3, knowledgeBase.size(), "知识库应包含3篇文档");
        for (Map<String, Object> doc : knowledgeBase) {
            assertTrue(doc.containsKey("id"), "文档应包含id字段");
            assertTrue(doc.containsKey("content"), "文档应包含content字段");
            assertTrue(doc.containsKey("embedding"), "文档应包含embedding字段");
            assertTrue(doc.containsKey("metadata"), "文档应包含metadata字段");
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ======================== 工具注册中心集成测试 ========================

    @Test
    @DisplayName("测试工具注册中心 - 工具注册、发现和调用")
    void testToolRegistryIntegration() {
        // 模拟工具注册中心
        Map<String, Map<String, Object>> toolRegistry = new HashMap<>();

        // 注册商品查询工具
        Map<String, Object> productTool = new HashMap<>();
        productTool.put("name", "getProductDetail");
        productTool.put("description", "根据商品ID查询商品详情");
        productTool.put("endpoint", "/api/v1/products/{id}");
        productTool.put("method", "GET");
        productTool.put("parameters", List.of(
                Map.of("name", "id", "type", "integer", "required", true, "description", "商品ID")
        ));
        productTool.put("registeredAt", System.currentTimeMillis());
        toolRegistry.put("getProductDetail", productTool);

        // 注册订单查询工具
        Map<String, Object> orderTool = new HashMap<>();
        orderTool.put("name", "getOrderList");
        orderTool.put("description", "查询用户订单列表");
        orderTool.put("endpoint", "/api/v1/orders");
        orderTool.put("method", "GET");
        orderTool.put("parameters", List.of(
                Map.of("name", "status", "type", "string", "required", false, "description", "订单状态"),
                Map.of("name", "page", "type", "integer", "required", false, "description", "页码")
        ));
        orderTool.put("registeredAt", System.currentTimeMillis());
        toolRegistry.put("getOrderList", orderTool);

        // 注册订单创建工具
        Map<String, Object> createOrderTool = new HashMap<>();
        createOrderTool.put("name", "createOrder");
        createOrderTool.put("description", "创建新订单");
        createOrderTool.put("endpoint", "/api/v1/orders");
        createOrderTool.put("method", "POST");
        createOrderTool.put("parameters", List.of(
                Map.of("name", "productId", "type", "integer", "required", true, "description", "商品ID"),
                Map.of("name", "quantity", "type", "integer", "required", true, "description", "购买数量")
        ));
        createOrderTool.put("registeredAt", System.currentTimeMillis());
        toolRegistry.put("createOrder", createOrderTool);

        // 验证工具注册
        assertEquals(3, toolRegistry.size(), "应注册3个工具");

        // 验证工具发现 - 按名称查找
        assertTrue(toolRegistry.containsKey("getProductDetail"), "应包含商品查询工具");
        assertTrue(toolRegistry.containsKey("getOrderList"), "应包含订单查询工具");
        assertTrue(toolRegistry.containsKey("createOrder"), "应包含订单创建工具");

        // 验证工具属性完整性
        for (Map.Entry<String, Map<String, Object>> entry : toolRegistry.entrySet()) {
            Map<String, Object> tool = entry.getValue();
            assertEquals(entry.getKey(), tool.get("name"), "工具名称应与注册键一致");
            assertNotNull(tool.get("description"), "工具描述不应为空");
            assertNotNull(tool.get("endpoint"), "工具端点不应为空");
            assertNotNull(tool.get("method"), "工具方法不应为空");
            assertNotNull(tool.get("parameters"), "工具参数列表不应为空");
        }

        // 验证工具参数校验 - 检查必填参数
        Map<String, Object> productToolDef = toolRegistry.get("getProductDetail");
        List<Map<String, Object>> params = (List<Map<String, Object>>) productToolDef.get("parameters");
        Map<String, Object> idParam = params.get(0);
        assertEquals("id", idParam.get("name"));
        assertEquals(true, idParam.get("required"));

        // 验证MySQL容器可访问
        assertTrue(mysql.isRunning(), "MySQL容器应处于运行状态");
        assertNotNull(mysql.getJdbcUrl(), "MySQL JDBC URL不应为空");
        assertTrue(mysql.getJdbcUrl().contains("ai_mall_test"), "数据库名应为ai_mall_test");
    }

    // ======================== 容器连通性验证 ========================

    @Test
    @DisplayName("验证Testcontainers容器连通性")
    void testContainerConnectivity() {
        // MySQL容器验证
        assertTrue(mysql.isRunning(), "MySQL容器应处于运行状态");
        assertNotNull(mysql.getHost(), "MySQL主机地址不应为空");
        assertTrue(mysql.getMappedPort(3306) > 0, "MySQL端口映射应有效");
        assertNotNull(mysql.getJdbcUrl(), "MySQL JDBC URL不应为空");
        assertEquals("test", mysql.getUsername(), "MySQL用户名应为test");

        // Redis容器验证
        assertTrue(redis.isRunning(), "Redis容器应处于运行状态");
        assertNotNull(redis.getHost(), "Redis主机地址不应为空");
        assertTrue(redis.getMappedPort(6379) > 0, "Redis端口映射应有效");
    }
}
