# AI-Mall：AI 增强电商系统

基于 **Spring Boot 3.5 + JDK 21** 的 AI 增强电商系统，集成智能客服、智能运维、自动化测试三大 AI Agent 能力。大模型**默认运行在本地 Ollama**（生成用 `qwen3.5-noVL`、向量化用 `bge-m3`，零外网、零 API Key），亦可切换到小米 MiMo / OpenAI 兼容云服务。

## 项目亮点

- **完整电商系统**：基于 mall 项目改造，包含商品、订单、用户等核心领域模块
- **三大 AI Agent**：
  - 智能客服 Agent（RAG + ReAct 架构，含输入净化 / 输出护栏 / 工具鉴权 / 审计闭环）
  - 智能运维 Agent（多 Agent 协作 + 事件驱动 + 3-Sigma/EWMA 双算法异常检测）
  - 自动化测试 Agent（OpenAPI 发现 + Spring AI 生成用例）
- **本地优先、可上云**：默认本地 Ollama（qwen3.5-noVL 生成 + bge-m3 向量化，免外网免 Key），可选接入小米 MiMo / OpenAI 兼容云模型；向量库 Milvus + 知识图谱 Neo4j
- **RAG 全链路**：文档解析 → 分块 → 向量化（Milvus）→ 关键词检索（BM25）→ RRF 融合 → 特征重排 → 生成
- **CI/CD**：GitHub Actions + Docker 多阶段构建 + Kubernetes 部署（见 `.github/workflows/` 与 `infra/`）

## 技术栈

| 层次 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 21 |
| 核心框架 | Spring Boot / Spring Cloud | 3.5 / 2024.0 |
| ORM | MyBatis Plus | 3.5.7 |
| 安全 | Spring Security + JWT | - |
| 搜索 | Elasticsearch | 8.15 |
| 缓存 | Redis | 7.x |
| 消息队列 | Kafka + RabbitMQ | 3.x / 3.13 |
| 向量数据库 | Milvus | 2.4 |
| 知识图谱 | Neo4j | 5.x |
| 文档库 | MongoDB | 7.x |
| AI 框架 | Spring AI | 1.0 |
| 大模型 | 本地 Ollama（qwen3.5-noVL 生成 / bge-m3 向量化，零外网零 Key）；可选 MiMo / OpenAI 兼容云模型 | 默认本地 |
| 测试 | JUnit 5 + Mockito | - |

## 项目结构

```
ai-mall/
├── pom.xml                          ← 父 POM（聚合 11 个模块）
├── mall-core/                       ← 核心基础模块
│   ├── mall-common/                 ← 工具类、通用组件、模型熔断/路由、自动配置
│   ├── mall-mbg/                    ← MyBatis Generator 生成代码与实体
│   └── mall-security/               ← Spring Security + JWT + 动态权限
├── mall-admin/                      ← 后台管理系统 API
├── mall-portal/                     ← 前台商城 API（依赖 RabbitMQ/MongoDB）
├── mall-search/                     ← Elasticsearch 商品搜索
├── mall-demo/                       ← 演示页面
├── mall-generator/                  ← 代码生成器
├── mall-message/                    ← 消息推送（Kafka/Mail）
├── ai-gateway/                      ← Spring Cloud Gateway 统一入口
├── agent-customer/                  ← 智能客服 Agent（ReAct + RAG）
├── agent-ops/                       ← 智能运维 Agent（事件总线）
├── agent-test/                      ← 自动化测试 Agent
├── infra/                           ← docker-compose、K8s 部署等
├── frontend/                        ← Vue 3 后台运营端
├── frontend-app/                    ← UniApp 商城端
├── scripts/                         ← 本地数据库与演示启动脚本
├── docker-compose.yml               ← Redis/ES 等本地中间件（不包含 MySQL）
└── .github/workflows/               ← CI/CD
```

## 快速开始

### 环境要求

- JDK 21、Maven 3.9+、Node.js 20+
- Windows 本机 MySQL 9.7（默认 `127.0.0.1:3306`）
- Docker 24+（含 Docker Compose v2）

### 启动步骤

```powershell
# 1. 初始化本机 MySQL（密码会安全提示输入，不写入仓库）
.\scripts\init-local-db.ps1

# 2. 一键启动课设演示闭环：核心商城 + 三个 Agent + Milvus/Neo4j + 后台前端
.\scripts\start-demo.ps1

# 3. 演示结束后停止由脚本启动的进程
.\scripts\stop-demo.ps1
```

初始化脚本检测到已有 `mall` 数据库时会默认停止，避免误覆盖数据。确认已备份且需要重置
演示数据时，显式运行 `.\scripts\init-local-db.ps1 -ResetDemoData`。

启动后访问 `http://localhost:5173`。后台账号为 `admin / Admin@123`；商城账号为
`demo / Demo@123`。这两个账号只用于本地演示，部署环境必须替换。

如果不使用脚本，可通过 `MALL_DB_URL`、`MALL_DB_USERNAME`、`MALL_DB_PASSWORD`
覆盖所有服务的数据库连接。默认 URL 指向本机 `mall` 库的 3306 端口。

### 服务端口（本地开发）

| 服务 | 端口 | 说明 |
|------|------|------|
| ai-gateway | 8080 | 统一入口（推荐通过网关访问） |
| mall-admin | 8081 | 后台管理系统 API |
| mall-search | 8082 | 商品搜索 |
| agent-customer | 8083 | 智能客服 |
| agent-ops | 8084 | 智能运维 |
| agent-test | 8085 | 自动化测试 |
| mall-generator | 8086 | 代码生成器 |
| mall-portal | 8087 | 前台商城 API |
| mall-demo | 8088 | 演示页面 |
| mall-message | 8090 | 消息服务 |
| admin-web | 5173 | Vue 3 后台运营端 |

网关路由（无注册中心，直连地址可用 `GATEWAY_ROUTE_*` 环境变量覆盖）：

| 网关路径 | 上游服务 |
|---------|---------|
| `/admin/**` | mall-admin (8081) |
| `/api/**` | mall-portal (8087) |
| `/search/**` | mall-search (8082) |
| `/agent/customer/**` | agent-customer (8083) |
| `/agent/ops/**` | agent-ops (8084) |
| `/agent/test/**` | agent-test (8085) |

### 基础设施端口

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL | 3306 | Windows 本机服务；密码由 `MALL_DB_PASSWORD` 或脚本输入 |
| Redis | 6379 | 本地开发无密码 |
| RabbitMQ | 5672 / 15672 | 账号 mall/mall，vhost /mall |
| Kafka | 9092 | - |
| MongoDB | 27017 | - |
| Milvus | 19530 | 向量数据库 |
| Neo4j | 7474 / 7687 | 账号 neo4j/neo4j123 |
| Elasticsearch | 9200 | 本地开发关闭安全认证 |
| MinIO | 9000 / 9001 | minioadmin/minioadmin |

## Agent 课设演示

启动前确保本机 Ollama 已运行，并具备 `qwen3.5-noVL:latest` 与 `bge-m3` 模型。

- **智能客服**：`bge-m3 → Milvus ANN` 与 Redis BM25 双路召回，RRF 融合、特征重排后交给本地 Ollama 生成；首次启动自动写入退货、运费和支付知识。
- **智能运维**：自动建立指标基线后注入异常，展示 Monitor → Bayesian/Neo4j RCA → Playbook → Change Gate 全链路，并由 Ollama 生成根因摘要。
- **自动化测试**：从 OpenAPI 优先发现真实接口，异常时使用项目内置契约；安全演示模式最多执行 8 个只读 GET API，输出状态码、响应耗时、JSON/Schema 与业务语义断言。

需要展示 Ollama 扩写测试用例时，可在启动前设置 `TEST_AGENT_AI_ENABLED=true`；默认关闭是为了保证课堂现场速度与可复现性。

## API 文档

各服务启用 springdoc，启动后访问对应服务：

- mall-admin: `http://localhost:8081/swagger-ui.html`
- mall-portal: `http://localhost:8087/swagger-ui.html`
- 智能客服对话: `POST http://localhost:8083/api/v1/chat`
- 客服反馈（人工校对闭环）: `POST http://localhost:8083/api/v1/feedback`

## 智能客服安全设计（防"乱说"）

- **输入净化**：中英文注入模式、零宽字符剥离、全角归一化、Base64/URL 编码还原检测
- **事实约束**：答案必须来自工具返回值或知识库检索；检索为空时直接拒答，不把空上下文交给模型
- **工具鉴权**：工具分 PUBLIC/USER_DATA/USER_WRITE 三级，未登录拒绝（fail-closed），订单查询绑定用户并做归属校验
- **输出护栏**：拦截空回答、推理结构泄露、无事实来源的金额/单号；手机号/身份证等 PII 脱敏后才可出站
- **审计闭环**：提问、工具调用、护栏拦截、用户反馈全部留痕（Redis），支持人工校对与幻觉率统计

## CI/CD

- **CI**：push/PR 到 main → Java 21 编译 + 指定模块 `mvn test` + JaCoCo 覆盖率上报
- **CD**：`v*` 标签触发 → 构建 Docker 镜像（`infra/docker-compose.prod.yml` 多阶段构建）→ 推送 → K8s 部署

## 测试

各模块单元测试已就绪（JUnit 5 + Mockito；`benchmark` 分组需 Docker，默认跳过）：

```bash
mvn -pl mall-core/mall-common test          # 49 例
mvn -pl agent-customer test                 # 41 例
mvn -pl mall-core/mall-common,mall-admin,mall-portal,ai-gateway test -am
```

各模块真实用例数以 `mvn test` 的 surefire 输出为准，不再在此维护可能失真的统计表。

## 已知边界（如实说明）

- 大模型默认走**本地 Ollama**，**无需任何 API Key、零外网**即可跑通全部 Agent 链路；仅当切换到小米 MiMo / OpenAI 云服务时才需配置 `MIMO_API_KEY` / `OPENAI_API_KEY` 与 `AI_OPENAI_BASE_URL`（见各模块 `application.yml`）。未配置云服务变量时不走云链路，也不影响本地 Ollama 运行
- 智能客服的知识库（Milvus/Redis 索引）需要先通过文档导入接口写入数据，索引为空时 RAG 会直接拒答
- 智能运维 Agent 的异常检测效果由离线评测 `AnomalyDetectionBenchmarkTest` 实测得出，不再预置任何演示数据。评测在带 ground-truth 标签的**合成**时序数据上进行（20 场景 × 600 点，注入尖峰/阶跃/漂移/噪声放大四类异常，固定随机种子可复现），非生产数据回放；运行 `mvn -pl agent-ops test -Dtest=AnomalyDetectionBenchmarkTest` 可复现全部指标
- 评测结论：AND 投票相对单算法可将误报率降低约 73%（vs 3-Sigma）~86%（vs EWMA），精确率 92.31%→94.61%，但召回率同步下降（39.24%→15.53%），F1 低于单用 3-Sigma。这是"以召回换精度"的取舍，在告警疲劳为痛点的场景下成立，选型依据见评测输出
- 本地 MySQL 不由 Docker 管理；`docker-compose.yml` 仅负责 Redis、MinIO 等中间件

## 项目归属与第三方说明

AI-Mall 的运行入口、AI Agent、网关、数据初始化、本地演示链路和前端品牌均由本项目维护，
不依赖公众号、外部体验账号或上游在线 API。项目演进自 Apache-2.0 许可的 mall 生态代码，
许可证与第三方来源说明保留在 `LICENSE`/各前端许可证及 `THIRD_PARTY_NOTICES.md` 中；
保留这些法定归属不影响本项目独立运行和自主维护。
