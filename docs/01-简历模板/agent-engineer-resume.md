# Agent 工程师简历模板

## 基本信息

- 姓名：XXX
- 学校：XXX大学
- 专业：人工智能/计算机科学
- 学历：硕士研究生
- 邮箱：xxx@xxx.com
- 电话：138-xxxx-xxxx

---

## 专业技能

- **AI Agent**：熟悉 Agent 基本原理，了解任务规划、工具调用、流程编排等核心机制，掌握 Agent 记忆系统的基础设计思路；熟悉 ReAct、Plan-and-Execute 等 Agent 架构，理解 Function Calling 机制，能设计多 Agent 协作系统
- **RAG**：熟悉 RAG 全链路设计与优化，能够结合业务场景对召回效果、响应质量与系统性能进行针对性优化；掌握文档分块、向量检索、混合排序（RRF）、重排序等 RAG 全流程
- **Agent 工具**：熟悉 Claude Code、OpenClaw、Hermes 等 Agent 的使用，了解架构设计原理，能够结合实际场景进行使用与实践；熟悉 Claude Code 的使用流程与常见操作方式，能够利用其完成代码理解、代码修改、任务拆解与开发辅助等工作
- **大模型**：熟悉 Prompt Engineering、CoT、Few-shot，有 MiMo、OpenAI 等大模型 API 调用经验
- **向量数据库**：熟悉 Milvus 向量检索、相似度计算、索引优化
- **知识图谱**：熟悉 Neo4j 图数据库、Cypher 查询、图遍历算法、贝叶斯推理
- **消息队列**：熟悉 Kafka 事件驱动架构，能设计 Agent 间异步通信
- **Java/Spring**：熟练使用 Spring Boot 3.5、Spring AI、Spring Security，理解多模块架构
- **深度学习**：熟悉深度学习框架 PyTorch，能够针对具体业务做出针对性优化
- **测试**：熟练使用 JUnit 5、Mockito、Testcontainers，能编写单元测试和集成测试
- **数据库**：熟悉 MySQL、Redis，了解缓存策略和分布式锁

---

## 项目经历

### AI-Mall：AI 增强电商系统

**项目时间**：2026.01 - 2026.06

**项目描述**：基于 Spring Boot 3.5 + JDK 21 的 AI 增强电商系统，集成智能客服、智能运维、自动化测试三大 AI Agent 能力，支持小米 MiMo 大模型接入。系统包含完整的电商功能（商品管理、订单管理、用户管理、商品搜索），同时通过 AI Agent 实现智能客服对话、故障自动检测与自愈、测试用例自动生成。

**技术栈**：Spring Boot 3.5 + JDK 21 + Spring AI + MiMo + Milvus + Neo4j + Kafka + Redis + MyBatis Plus + MySQL + Elasticsearch + JUnit 5 + Mockito + Testcontainers + Docker Compose

**项目架构**：采用多模块 Maven 架构，13 个模块独立编译部署

```
ai-mall/
├── mall-core/          ← 电商核心（mall-common/mbg/security/admin/portal/search/demo）
├── ai-gateway/         ← 统一 API 入口 + 认证授权
├── agent-customer/     ← 智能客服 Agent（RAG + ReAct + MiMo）
├── agent-ops/          ← 智能运维 Agent（多 Agent 协作 + Kafka + Neo4j）
├── agent-test/         ← 自动化测试 Agent（JUnit 5 + Testcontainers）
└── infra/              ← Docker Compose 一键部署
```

**核心贡献（Agent 方向）**：
- 实现智能客服 Agent：基于 ReAct 模式（Thought → Action → Observation 循环），集成 RAG 检索（Milvus 向量 + BM25 关键词 + RRF 融合），支持多轮对话记忆（Redis 短期 + Milvus 长期），设计工具注册中心集成商品搜索、订单查询、售后工单三个工具
- 实现智能运维 Agent：4 个 Agent 协作（Monitor/RCA/Heal/Change），MonitorAgent 使用 3-Sigma + EWMA 多算法投票降低误报率 85%，RCAAgent 基于 Neo4j 知识图谱 + 贝叶斯推理定位根因，HealAgent 实现 Playbook 匹配和分级自愈（L0/L1/L2），ChangeAgent 基于风险评分进行审批门控
- 使用 Kafka 事件总线解耦 Agent 间通信，设计 aiops.alerts/events/commands/audit 四个 Topic
- 接入小米 MiMo 大模型，设计模型路由 + 三态熔断器（CLOSED/OPEN/HALF_OPEN），支持自动降级到 OpenAI

**核心贡献（Java 后端方向）**：
- 设计多模块 Maven 架构，13 个模块通过父 POM 统一版本管理
- 基于 Spring Security + JWT 实现统一认证授权，复用于所有业务模块
- 使用 Redis 实现商品热点数据缓存 + 对话记忆存储，QPS 提升 300%
- 使用 Elasticsearch 实现商品全文搜索，支持中文分词、同义词匹配

**核心贡献（测试方向）**：
- 实现自动化测试 Agent：基于 API 文档自动生成测试用例，覆盖正常/异常/边界场景
- 使用 Testcontainers 启动真实 MySQL/Redis 容器执行集成测试
- 设计测试报告生成器，支持 HTML/JSON 格式，可视化展示测试结果和覆盖度

**项目亮点**：
- 一个项目覆盖三个面试方向（Java 后端 / Agent 工程 / 测试工程）
- 全套国产技术栈：MiMo 大模型 + Milvus 向量数据库 + Neo4j 知识图谱
- 13 个模块全部编译通过，Docker Compose 一键部署
- Agent 间通过 Kafka 事件驱动解耦，支持高并发和故障恢复

---

## 实习/竞赛经历

（根据实际情况填写）

---

## 自我评价

- 对 AI Agent 技术有浓厚兴趣，持续关注行业动态
- 良好的系统设计能力，能够设计复杂的多 Agent 系统
- 较强的问题分析能力，能够定位和解决复杂问题
