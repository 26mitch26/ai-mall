# 简历实证索引（Interview Evidence）

本目录把简历中每一个"数字卖点"沉淀为 **设计目标 + 实测数据 + 复现命令**，面试被追问时可直接指到代码与报告，全部可在本机一键复现。

| # | 简历卖点 | 设计目标 | 实测数据 | 证据文件 | 复现命令 |
|---|---------|---------|---------|---------|---------|
| 1 | Redis 缓存 QPS 提升 300% | 真实 MySQL 延迟（15~35ms）下 ≥ 3.0x | 商品查询 **12.83x**、对话记忆 **17.66x**（600%+ ~ 1700%+） | [cache-qps-validation.md](cache-qps-validation.md) | 见下文 §2 |
| 2 | RAG 混合检索显著提升召回率 | 混合链路 Recall@1 显著高于纯向量基线 | Recall@1 **75.0% → 83.3%**（+8.3pp）、Recall@3 **91.7% → 100.0%**、MRR 0.823 → 0.910 | [rag-recall-validation.md](rag-recall-validation.md) | 见下文 §3 |
| 3 | PDF/Word/HTML/TXT 非结构化知识自动化 | 一键把网页符号、扫描排版转为清洗后纯文本知识 | 4 类文件现场构造 → 解析 → 断言全绿 | [rag-recall-validation.md](rag-recall-validation.md#文档解析管线) | 见下文 §4 |
| 4 | 语义检索升级：bge-m3 中文语义向量（实测） | 换语义 embedding 解锁词法 embedding 的召回天花板 | 纯向量 Recall@1 **75% → 87.5%**（Recall@3 100%、MRR 0.938）；规范标签 V5 达 91.7%（MRR 0.958）| [rag-recall-validation.md](rag-recall-validation.md#51-语义-embedding-实测) | 见下文 §5 |

---

> 📌 **面试叙事**：想讲"为什么这么改、否决了什么、深层洞察"的完整故事线，见 **[rag-interview-notes.md](rag-interview-notes.md)**（含"混合检索不一定优于纯向量"的反直觉实证）。

---

## §1 项目版本与环境

- 语言/框架：Java 21 / Spring Boot 3.5 / Maven 多模块
- 检索组件：Milvus（向量）+ Redis（BM25 倒排索引）+ 特征工程 Reranker
- 压测计数：每场景 5,000 请求、50 并发、10 个热点 key

## §2 缓存 QPS 实测（一键复现）

```bash
# 1) 启动本地 Redis（707 端口 16379 已占用则换端口并配 REDIS_BENCH_PORT）
docker run -d -p 16379:6379 --name mall-redis-bench redis:7-alpine

# 2) 直连压测（Windows PowerShell 注意 -D 参数整体加引号）
mvn -pl mall-core/mall-common test "-Dtest=CacheBenchmarkLiveTest" "-Dexcluded.groups=none" "-Djacoco.skip=true"
```

测试类 / 引擎：
[CacheBenchmarkLiveTest.java](../mall-core/mall-common/src/test/java/com/ai/mall/common/benchmark/CacheBenchmarkLiveTest.java)
[CacheBenchmarkService.java](../mall-core/mall-common/src/main/java/com/ai/mall/common/common/benchmark/CacheBenchmarkService.java)

## §3 RAG 召回率评测（离线可复现，零外部依赖）

```bash
mvn -pl agent-customer test "-Dtest=RagRecallEvaluationTest" "-Djacoco.skip=true"
```

- 报告中两条链路全量指标及断言结果自动写入 `agent-customer/target/rag-recall-report.txt`
- 评测代码：
  - 评测器：[RagRecallEvaluator.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/RagRecallEvaluator.java)
  - 评测集：[KnowledgeBaseFixture.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/KnowledgeBaseFixture.java)
  - 测试入口：[RagRecallEvaluationTest.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/RagRecallEvaluationTest.java)

## §4 文档解析管线（一键复现）

```bash
mvn -pl agent-customer test "-Dtest=KnowledgeParserTest" "-Djacoco.skip=true"
```

- 解析器：[KnowledgeFileParser.java](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/rag/KnowledgeFileParser.java)
- 测试：[KnowledgeParserTest.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/service/rag/KnowledgeParserTest.java)

## §5 语义 Embedding 实测（bge-m3，需本机 Ollama 在线）

```bash
# 前置：本机 ollama serve 在线且已 ollama pull bge-m3
mvn -pl agent-customer test "-Dtest=RagRecallSemanticTest" "-Drag.embed.model=bge-m3" "-Djacoco.skip=true"
```

- 完整链路与数据落盘 `agent-customer/target/rag-recall-semantic-report.txt`
- 语义模型封装：[OllamaEmbeddingModel.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/OllamaEmbeddingModel.java)
- 语义评测入口：[RagRecallSemanticTest.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/RagRecallSemanticTest.java)