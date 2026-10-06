# 简历实证索引（Interview Evidence）

AI 产品经理作品请优先阅读 [产品方案](product/ai-pm-product-brief.md)、[竞品与商业验证](product/competitive-and-commercial-plan.md)
和 [本轮 Agent 产品评测](../eval/README.md)。下方为历史工程实证材料；未在本轮重跑的性能数字，不应混写成本轮效果，更不能当作线上商业收益。

本目录把简历中每一个"数字卖点"沉淀为 **设计目标 + 实测数据 + 复现命令**，面试被追问时可直接指到代码与报告，全部可在本机一键复现。

| # | 简历卖点 | 设计目标 | 实测数据 | 证据文件 | 复现命令 |
|---|---------|---------|---------|---------|---------|

---

## 面试文档导航

| 文档 | 面向 | 内容 |
|---|---|---|
| [interview-story.md](interview-story.md) | 所有人 | 30 秒电梯陈述、3 分钟项目介绍、简历写法、6 个 STAR 故事、三个「不要说的话」 |
| [interview-agent-understanding.md](interview-agent-understanding.md) | AI/Agent 岗 | 我理解的 Agent（三个必要条件）、与传统软件的边界、项目里用到的 6 种 Agent 能力、选型判断、踩过的坑 |
| [interview-backend.md](interview-backend.md) | 传统后端岗 | 模块划分、统一响应与全局异常、分页/索引/事务、幂等三段式、Redis 七种用途、并发、限流熔断降级、中间件、可观测性、部署与 CI/CD、12 个亮点 |
| [interview-customer-agent.md](interview-customer-agent.md) | 客服/检索岗 | 混合检索为什么要双路、证据不足即拒答、记忆隔离、三级工具鉴权、语义缓存三道护栏、护栏边界、追问预案 |
| [interview-test-agent.md](interview-test-agent.md) | 测试/平台岗 | 期望值治理（OpenAPI 当 oracle）、断言分层、噪音分类、环境归因、缺陷回流、质量评测（LLM judge）、MCP 接入三个设计决定 |
| [interview-ops-agent.md](interview-ops-agent.md) | SRE/运维岗 | 检测算法与取舍、图 + 朴素贝叶斯（含真实 bug 故事）、事件驱动四 Agent、门控闭环、模拟执行器、修过的部署缺陷 |
| [interview-qa.md](interview-qa.md) | 所有人 | 综合高频问答 + 「不能说的话」对照表 + 一分钟自检清单 |
| [agent-quality-evaluation.md](agent-quality-evaluation.md) | 所有人 | 客服质量评测的指标口径、取样策略与五条诚实声明 |

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
[CacheBenchmarkService.java](../mall-core/mall-common/src/test/java/com/ai/mall/common/benchmark/CacheBenchmarkService.java)

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

## 当前文档入口（2026-10-07）

[最终实验报告](../实验报告-最终版.md)、[Agent产品评测](../eval/product-evaluation-report.md)、[分块取舍](product/chunk-strategy-review.md)、[模型入口](product/model-selection.md)、[投递项目段落](product/resume-project-section.md) 与 [文档核验清单](product/document-sync-audit.md)。历史工程数值仍保留其原条件，不能覆盖后续任务失败或合并阶段测试数。
