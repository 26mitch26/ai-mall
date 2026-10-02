package com.ai.mall.agent.customer.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 连接与向量库配置。
 * <p>
 * 压测根因修复：Milvus Java SDK 默认 {@code idleTimeout=30s}——gRPC 通道空闲超过 30 秒后
 * 会被回收，而 Spring AI 的 MilvusVectorStore 内部持有单例通道，并发下复用已关闭的通道，
 * 直接抛 {@code TimeoutException: Idle timeout expired} / {@code ClosedChannelException}（压测时
 * 30 并发 46.72% 失败）。此处显式创建 {@link MilvusServiceClient}：
 * 1. 调大 idleTimeout（默认 180s，仍保证空闲回收，避免连接泄漏）；
 * 2. 开启 gRPC keepalive（20s 探测包 + 5s 判定超时 + 允许无调用时保活），
 *    让心跳持续维持通道活性，从根本上避免"通道被回收后再复用"。
 * <p>
 * 同时 override 了 Spring AI 自动装配的 VectorStore，注入带上述连接参数的客户端。
 */
@Configuration
public class MilvusVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStoreConfig.class);

    @Value("${milvus.client.host:localhost}")
    private String host;

    @Value("${milvus.client.port:19530}")
    private int port;

    /** 空闲回收阈值，默认 180s（原 SDK 默认 30s 是压测崩溃主因） */
    @Value("${milvus.client.idle-timeout-seconds:180}")
    private long idleTimeoutSeconds;

    /** gRPC keepalive 探测间隔 */
    @Value("${milvus.client.keep-alive-time-seconds:20}")
    private long keepAliveTimeSeconds;

    /** keepalive 探测无响应的判定超时 */
    @Value("${milvus.client.keep-alive-timeout-seconds:5}")
    private long keepAliveTimeoutSeconds;

    /** 向量集合名：知识库改版需要"清空重灌"时更换集合名即可获得全新向量空间 */
    @Value("${milvus.collection-name:ai_mall_kb}")
    private String collectionName;

    /** 长期记忆专用集合名：与知识库集合隔离，防止历史对话被知识检索命中 */
    @Value("${milvus.memory-collection-name:ai_mall_chat_memory}")
    private String memoryCollectionName;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withIdleTimeout(idleTimeoutSeconds, TimeUnit.SECONDS)
                .withKeepAliveTime(keepAliveTimeSeconds, TimeUnit.SECONDS)
                .withKeepAliveTimeout(keepAliveTimeoutSeconds, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
        log.info("初始化 MilvusServiceClient: host={}, port={}, idleTimeout={}s, keepAliveTime={}s, keepAliveWithoutCalls=true",
                host, port, idleTimeoutSeconds, keepAliveTimeSeconds);
        return new MilvusServiceClient(connectParam);
    }

    @Bean
    @Primary
    public VectorStore vectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(true)
                .build();
    }

    /**
     * 长期记忆向量库（独立集合），由 MemoryService 按 Bean 名注入。
     * 与知识库共用集合时，历史对话会被知识检索命中并作为"来源"展示（实测踩坑），故物理隔离。
     */
    @Bean
    public VectorStore memoryVectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName(memoryCollectionName)
                .initializeSchema(true)
                .build();
    }
}