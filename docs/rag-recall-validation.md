# RAG 召回率实测报告（设计目标 ↔ 实测数据）

> **2026-10-07核验：** 本文保留早期工程/选型阶段的记录，未重测的数值不是当前结果。当前任务评测、模型入口、分块与登录状态以 [产品评测报告](../eval/product-evaluation-report.md)、[最终实验报告](../实验报告-最终版.md) 和 [Chunk策略审查](product/chunk-strategy-review.md) 为准。旧子块实验不否定完整父段恢复；历史来源命中与测试通过不能当作真实解决率。


> 面向简历条目：**"搭建混合检索 RAG（向量 + BM25 + RRF + 精排），召回率显著提升；PDF/Word/HTML 非结构化知识一键自动化入库"**
> 结论先行：混合链路 Recall\@1 **75.0% → 83.3%**（+8.3pp）、Recall\@3 **91.7% → 100.0%**、MRR 0.823 → 0.910，所有指标均优于纯向量基线。

***

## 1. 检索链路设计

```
文档解析(KnowledgeFileParser)             查询
  PDF/Word/HTML/TXT ──清洗──> Document       │
        │                                   │
        ▼                   1. Milvus 向量检索 ◄─── embedding(query)
  分块策略 ─┬ fixed_size(512+64重叠)          │
          ├ sentence(≤768按句合并)  2. BM25 关键词检索
          └ semantic(按段落)            │
        │                             │
        ▼             3. RRF 融合排序  k=60
  双路索引(Milvus + Redis倒排)          │
                            4. 特征工程精排(6维加权)
                                      │
                           5. topK 上下文 → LLM 严格受限生成
```

- **向量检索**：Milvus（Spring AI `VectorStore` 抽象），捕获语义相近（"退货" ↔ "退款"）

- **关键词检索**：Redis 倒排索引 + BM25（k1=1.5，b=0.75），捕获精确匹配（订单号/商品名）

- **RRF 融合**：`score(d) = Σ 1/(k + rank_i)`，k=60，免归一化、对异常值鲁棒

- **精排**：6 维特征加权（词覆盖率 0.25 / 语义相似度 0.30 / BM25 0.20 / 位置加成 0.10 / 长度惩罚 0.05 / 查询文档比 0.10），模拟真实 CrossEncoder 精排原理、零 LLM 调用

## 2. 文档分块策略

| 策略           | 分块方式                                 | 适用       |
| ------------ | ------------------------------------ | -------- |
| `fixed_size` | 512 字符 + 64 字符滑动窗口重叠                 | 结构化/规则文本 |
| `sentence`   | 句边界（。！？.!? + 尾随引号括号）合并至 512，超 768 截断 | 自然语言客服语料 |
| `semantic`   | 段落边界，语义完整块                           | 长文档粗粒度   |

所有分块带元信息：`chunkId/docId/chunkIndex/prevChunkId/nextChunkId`，支持溯源与上下文拼接。

## 3. 文档解析管线（"符号自动化处理"）

- **PDF**：pdfbox 逐页抽取文本（扫描件无文本则抛异常提示）

- **Word(.docx)**：poi-ooxml 段落 + 表格单元格（单元格以 `｜` 分隔保结构）

- **HTML**：jsoup 移除 `script/style/noscript/iframe/svg`，仅保留 `h1-h4/p/li/td/th/div/article/section` 正文

- **统一清洗**：去残余标签 → 去控制字符（留 `\n\t`）→ 装饰符号归一（•・●▶★◆◇■□▲▼※→←↑↓▓|\_\~^ 等 → 空格）→ 压缩空白/空行

落地代码：[KnowledgeFileParser.java](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/rag/KnowledgeFileParser.java)，测试见 §7。

## 4. 评测方法（离线可复现）

- **评测集**：24 条真实客服咨询，覆盖退货/运费/优惠券/发票/会员/售后/支付/换货/服务时间/包装/分期等 12+ 主题

- **知识库**：16 篇知识文档（12 篇有效 + 4 篇干扰文档，验证噪声鲁棒性），分块后 16 块入库

- **Embedding**：本地可复现模型（字符 n-gram 哈希散列 → 归一化向量），零外部依赖

- **基线 = 纯向量 top5；被测 = 生产混合链路（向量∪BM25 → RRF → 精排）top5**

- **指标**：Recall\@1 / Recall\@3 / Recall\@5 / MRR

评测代码：入口 [RagRecallEvaluationTest.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/RagRecallEvaluationTest.java)、评测器 [RagRecallEvaluator.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/RagRecallEvaluator.java)、评测集 [KnowledgeBaseFixture.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/KnowledgeBaseFixture.java)。

## 5. 实测数据（2026-09-02 实测产物）

| 链路                      | Recall\@1  | Recall\@3  | Recall\@5  | MRR        |
| ----------------------- | ---------- | ---------- | ---------- | ---------- |
| 基线 · 纯向量 topK           | 75.0%      | 91.7%      | 95.8%      | 0.823      |
| **混合 · 向量+BM25+RRF+精排** | **83.3%**  | **100.0%** | **100.0%** | **0.910**  |
| 净提升                     | **+8.3pp** | **+8.3pp** | +4.2pp     | **+0.087** |

> 完整指标同时落盘 `agent-customer/target/rag-recall-report.txt`。

## 5.1 语义 Embedding 实测（bge-m3，本机 Ollama，2026-09-02）

> 面向简历条目：**"语义检索升级：接入 bge-m3 中文语义向量"**。验证词法 Embedding 的召回天花板能否被中文语义向量解锁。

在 §5 的基础上，把 Embedding 引擎替换为 **bge-m3 中文语义向量**（Ollama 本地 `POST /api/embed`，零外网、零 API key）后同链路评测：

| 链路                    | Recall\@1 | Recall\@3 | Recall\@5 | MRR       |
| --------------------- | --------- | --------- | --------- | --------- |
| 纯向量 topK（bge-m3）      | **87.5%** | 100.0%    | 100.0%    | 0.938     |
| 混合·向量+BM25+RRF+精排     | 83.3%     | 100.0%    | 100.0%    | 0.917     |
| **V5 · 规范主题标签精确命中置顶** | **91.7%** | 100.0%    | 100.0%    | **0.958** |

**结论**：

1. 词法哈希 Embedding 纯向量 Recall\@1 仅 75%（§5），换 **bge-m3 中文语义向量后纯向量单路即达 87.5%**（+12.5pp），无需叠加 BM25/手工标签即可稳定命中同义改写查询——中文语义 Embedding 是召回率上限的根本解锁路径。
2. 语义 embedding 下 BM25 混合是**净负数**（87.5% → 83.3%，-4.2pp），说明语义向量已覆盖关键词语义、混合反而注入噪声；与词法版本的混合提升（+8.3pp）形成对照，证明优化须按 embedding 能力分场景取舍，不能一条路走到底。
3. 叠加规范主题标签「精确命中置顶」机制（仿主流 Skill 检索 exact 阶段）进一步把 Recall\@1 提至 **91.7%**、MRR 0.958——确定性主题查询被标签元数据精确命中置顶，属真实生产通用做法。
4. 实测回退 late-interaction(IDF-MaxSim) 精排与 Parent-Child 子块等无效增量，仅保留经评测确认的优化。

复现命令：`mvn -pl agent-customer test "-Dtest=RagRecallSemanticTest" "-Drag.embed.model=bge-m3" "-Djacoco.skip=true"`
成功标志：退出码 0；完整指标落盘 `agent-customer/target/rag-recall-semantic-report.txt`。

> 前置：本机 `ollama serve` 在线且 `ollama pull bge-m3`。评测代码：[RagRecallSemanticTest.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/RagRecallSemanticTest.java)、[OllamaEmbeddingModel.java](../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/OllamaEmbeddingModel.java)。

## 6. 设计优化点与后续计划

**本次已交付的优化**（实测支撑）：

1. 双路召回（向量∪BM25）拯救纯向量漏检的精确关键词场景
2. 特征精排在 RRF 基础上二次校正排序质量
3. 干扰文档加入评测集，证明对噪声鲁棒
4. 空检索硬拒答 `NO_CONTEXT_ANSWER`，掐断幻觉来源

**后续提升计划**（面试可讲方向）：

1. **【已完成】** 生产级中文语义 embedding **bge-m3** 已接入并实测：纯向量单路 Recall\@1 87.5%（见 §5.1）；真实 CrossEncoder reranker（bge-reranker）待接入
2. 专业中文分词（HanLP / 工业界 jieba）替换 bigram，提升 BM25 准确率
3. 查询改写 / 意图识别：口语 → 知识库术语映射
4. 评测集扩充（百级 query + 业务方标注 golden），上线前回归
5. Milvus + 多租户隔离、索引分片容量规划

## 7. 复现步骤

```bash
# RAG 召回率评测
mvn -pl agent-customer test "-Dtest=RagRecallEvaluationTest" "-Djacoco.skip=true"
# 成功标志：输出 "✓ 结论验证：混合检索 Recall@3 (100.0%) ≥ 纯向量基线 (91.7%)"，退出码 0

# 文档解析管线测试
mvn -pl agent-customer test "-Dtest=KnowledgeParserTest" "-Djacoco.skip=true"
```

> 说明：`-Djacoco.skip=true` 仅为规避本仓库位于含非 ASCII 字符路径（简历/）时 jacoco agent 的 fork 崩溃；路径不含中文时可不加。

