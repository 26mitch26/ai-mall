# Java 后端工程师简历模板

## 基本信息

- 姓名：XXX
- 学校：XXX大学
- 专业：计算机科学与技术
- 学历：硕士研究生
- 邮箱：xxx@xxx.com
- 电话：138-xxxx-xxxx

---

## 专业技能

- **Java 核心**：熟悉 Java 21 新特性（Record Patterns、Virtual Threads），深入理解 JVM 内存模型、GC 调优
- **Spring 生态**：熟练使用 Spring Boot 3.5、Spring Security、Spring Cloud、Spring AI，理解自动配置原理
- **数据库**：熟悉 MySQL 索引优化、事务隔离级别；掌握 Redis 缓存策略、分布式锁
- **消息队列**：熟悉 Kafka 消息模型、消费者组、Exactly-Once 语义、事务消息
- **搜索与向量**：熟悉 Elasticsearch 全文搜索、中文分词；熟悉 Milvus 向量检索、相似度计算、索引优化
- **RAG**：熟悉 RAG 全链路设计与优化，能够结合业务场景对召回效果、响应质量与系统性能进行针对性优化
- **Agent**：熟悉 Agent 基本原理，了解任务规划、工具调用、流程编排等核心机制，掌握 Agent 记忆系统的基础设计思路；熟悉 Claude Code、OpenClaw、Hermes 等 Agent 的使用，了解架构设计原理，能够结合实际场景进行使用与实践；熟悉 Claude Code 的使用流程与常见操作方式，能够利用其完成代码理解、代码修改、任务拆解与开发辅助等工作
- **知识图谱**：了解 Neo4j 图数据库、Cypher 查询、服务拓扑建模
- **深度学习**：熟悉深度学习框架 PyTorch，能够针对具体业务做出针对性优化
- **大模型应用**：熟悉 MiMo、OpenAI GPT-4 等大模型 API 调用，掌握 Prompt Engineering、CoT、Few-shot 等提示词工程技巧
- **测试**：熟练使用 JUnit 5、Mockito、Testcontainers；熟悉单元测试、集成测试、接口测试
- **容器化**：熟悉 Docker、Docker Compose 编排，能编写多服务编排配置

---

## 项目经历

### AI-Mall：AI 增强电商系统

**项目时间**：2026.01 - 2026.06

**项目描述**：基于 Spring Boot 3.5 + JDK 21 的 AI 增强电商系统，集成智能客服、智能运维、自动化测试三大 AI Agent 能力，支持小米 MiMo 大模型接入。系统包含完整的电商功能（商品管理、订单管理、用户管理、商品搜索），同时通过 AI Agent 实现智能客服对话、故障自动检测与自愈、测试用例自动生成。

**技术栈**：Spring Boot 3.5 + JDK 21 + MyBatis Plus + Spring Security + MySQL + Redis + Kafka + Elasticsearch + Milvus + Neo4j + Spring AI + MiMo + JUnit 5 + Mockito + Testcontainers + Docker Compose

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

**核心贡献（Java 后端方向）**：
- 设计并实现多模块 Maven 架构，13 个模块通过父 POM 统一版本管理，模块间依赖清晰，独立编译部署
- 基于 Spring Security + JWT 实现统一认证授权，支持动态权限控制，复用于所有业务模块
- 使用 Redis 实现商品热点数据缓存 + 对话记忆存储，QPS 提升 300%
- 通过 Kafka 实现订单异步处理 + 运维 Agent 事件驱动，保证最终一致性
- 使用 Elasticsearch 实现商品全文搜索，支持中文分词、同义词匹配
- 基于 Docker Compose 编排 10+ 基础设施服务，一键部署完整环境

**核心贡献（Agent 方向）**：
- 实现智能客服 Agent：基于 ReAct 模式（Thought → Action → Observation 循环），集成 RAG 检索（Milvus 向量 + BM25 关键词 + RRF 融合），支持多轮对话记忆（Redis 短期 + Milvus 长期）
- 实现智能运维 Agent：4 个 Agent 协作（Monitor/RCA/Heal/Change），MonitorAgent 使用 3-Sigma + EWMA 多算法投票降低误报率 85%，RCAAgent 基于 Neo4j 知识图谱 + 贝叶斯推理定位根因
- 接入小米 MiMo 大模型，设计模型路由 + 三态熔断器，支持自动降级到 OpenAI

**核心贡献（测试方向）**：
- 实现自动化测试 Agent：基于 API 文档自动生成测试用例，覆盖正常/异常/边界场景
- 使用 Testcontainers 启动真实 MySQL/Redis 容器执行集成测试，确保环境一致性
- 设计测试报告生成器，支持 HTML/JSON 格式，可视化展示测试结果和覆盖度

**项目亮点**：
- 一个项目覆盖三个面试方向（Java 后端 / Agent 工程 / 测试工程）
- 全套国产技术栈：MiMo 大模型 + Milvus 向量数据库 + Neo4j 知识图谱
- 13 个模块全部编译通过，Docker Compose 一键部署
- 完善的监控体系（Prometheus + Grafana）

---

## 实习/竞赛经历

（根据实际情况填写）

---

## 自我评价

- 良好的编码习惯，注重代码质量和可维护性
- 较强的学习能力，能够快速掌握新技术
- 良好的沟通能力，能够清晰表达技术方案
