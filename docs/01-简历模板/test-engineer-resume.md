# 测试工程师简历模板

## 基本信息

- 姓名：XXX
- 学校：XXX大学
- 专业：软件工程/计算机科学
- 学历：硕士研究生
- 邮箱：xxx@xxx.com
- 电话：138-xxxx-xxxx

---

## 专业技能

- **测试框架**：熟练使用 JUnit 5、Mockito、Testcontainers，能设计完整的测试体系
- **自动化测试**：熟悉接口自动化测试、集成测试、端到端测试，能自动生成测试用例
- **测试策略**：了解测试金字塔、测试驱动开发（TDD）、行为驱动开发（BDD）
- **CI/CD**：熟悉 Jenkins、GitHub Actions 自动化构建和测试
- **性能测试**：了解 JMeter、Gatling 性能测试工具
- **Java/Spring**：熟练使用 Spring Boot 3.5、Spring Test、Spring Boot Test
- **AI Agent**：熟悉 Agent 基本原理，了解任务规划、工具调用、流程编排等核心机制；熟悉 Claude Code、OpenClaw、Hermes 等 Agent 的使用，能够利用其完成代码理解、代码修改、任务拆解与开发辅助等工作
- **RAG**：熟悉 RAG 全链路设计与优化，能够结合业务场景对召回效果、响应质量与系统性能进行针对性优化
- **深度学习**：熟悉深度学习框架 PyTorch，能够针对具体业务做出针对性优化
- **大模型应用**：熟悉 MiMo、OpenAI GPT-4 等大模型 API 调用，掌握 Prompt Engineering、CoT、Few-shot 等提示词工程技巧
- **数据库**：熟悉 MySQL、Redis，能使用 Testcontainers 启动真实数据库容器
- **容器化**：熟悉 Docker、Docker Compose，能编写测试环境编排配置

---

## 项目经历

### AI-Mall：AI 增强电商系统

**项目时间**：2026.01 - 2026.06

**项目描述**：基于 Spring Boot 3.5 + JDK 21 的 AI 增强电商系统，集成智能客服、智能运维、自动化测试三大 AI Agent 能力，支持小米 MiMo 大模型接入。系统包含完整的电商功能（商品管理、订单管理、用户管理、商品搜索），同时通过 AI Agent 实现智能客服对话、故障自动检测与自愈、测试用例自动生成。

**技术栈**：Spring Boot 3.5 + JDK 21 + JUnit 5 + Mockito + Testcontainers + Spring AI + MiMo + Milvus + Neo4j + Kafka + Redis + MyBatis Plus + MySQL + Elasticsearch + Docker Compose

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

**核心贡献（测试方向）**：
- 实现自动化测试 Agent：基于 API 文档自动生成测试用例，覆盖正常/异常/边界场景，TestCaseGenerator 解析 SpringDoc/OpenAPI 定义生成 ApiDefinition，自动构造 Parameter 组合
- 使用 Testcontainers 启动真实 MySQL/Redis 容器执行集成测试，确保测试环境与生产环境一致，测试完成后自动清理容器
- 实现 TestExecutor 支持模拟调用和真实环境测试，设计 TestReportGenerator 生成 HTML/JSON 格式测试报告，可视化展示测试结果和覆盖度
- 编写 mall-core 全模块单元测试，使用 Mockito Mock 外部依赖，单元测试覆盖率达到 80%+
- 使用 Spring Boot Test 编写接口测试，验证 REST API 正确性，CI 中通过率 100%

**核心贡献（Agent 方向）**：
- 实现智能客服 Agent：基于 ReAct 模式（Thought → Action → Observation 循环），集成 RAG 检索（Milvus 向量 + BM25 关键词 + RRF 融合），支持多轮对话记忆（Redis 短期 + Milvus 长期）
- 实现智能运维 Agent：4 个 Agent 协作（Monitor/RCA/Heal/Change），MonitorAgent 使用 3-Sigma + EWMA 多算法投票降低误报率 85%，RCAAgent 基于 Neo4j 知识图谱 + 贝叶斯推理定位根因
- 接入小米 MiMo 大模型，设计模型路由 + 三态熔断器，支持自动降级到 OpenAI

**核心贡献（Java 后端方向）**：
- 设计多模块 Maven 架构，13 个模块通过父 POM 统一版本管理
- 基于 Spring Security + JWT 实现统一认证授权，复用于所有业务模块
- 使用 Redis 实现商品热点数据缓存 + 对话记忆存储，QPS 提升 300%
- 使用 Elasticsearch 实现商品全文搜索，支持中文分词、同义词匹配

**项目亮点**：
- 一个项目覆盖三个面试方向（Java 后端 / Agent 工程 / 测试工程）
- 自动化测试 Agent 自动生成用例 + 执行 + 报告，减少人工成本 60%
- Testcontainers 确保测试环境与生产环境一致，避免"在我机器上能跑"
- 单元测试覆盖 80%+，集成测试通过率 100%，测试执行时间从 30 分钟降到 5 分钟

---

## 实习/竞赛经历

（根据实际情况填写）

---

## 自我评价

- 注重细节，善于发现潜在问题
- 良好的测试思维，能够设计全面的测试用例
- 较强的分析能力，能够快速定位问题根因
