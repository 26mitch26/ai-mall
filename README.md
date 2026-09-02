# AI-Mall：AI 增强电商系统

基于 **Spring Boot 3.5 + JDK 21** 的 AI 增强电商系统，集成智能客服、智能运维、自动化测试三大 AI Agent 能力，支持小米 MiMo 大模型接入。

## 项目亮点

- **完整电商系统**：基于 mall 项目改造，包含商品、订单、用户等核心领域模块
- **三大 AI Agent**：
  - 智能客服 Agent（RAG + ReAct 架构，含输入净化 / 输出护栏 / 工具鉴权 / 审计闭环）
  - 智能运维 Agent（多 Agent 协作 + 事件驱动 + 3-Sigma/EWMA 双算法异常检测）
  - 自动化测试 Agent（OpenAPI 发现 + Spring AI 生成用例）
- **国产技术栈**：小米 MiMo 大模型 + Milvus 向量数据库 + Neo4j 知识图谱
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
| 大模型 | 小米 MiMo（可切 OpenAI 兼容） | - |
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
├── docker-compose.yml               ← 本地开发基础设施编排
└── .github/workflows/               ← CI/CD
```

## 快速开始

### 环境要求

- JDK 21、Maven 3.9+
- Docker 24+（含 Docker Compose v2）

### 启动步骤

```bash
# 1. 启动基础设施（MySQL/Redis/ES/Kafka/RabbitMQ/MongoDB/Milvus/Neo4j/MinIO）
docker compose up -d

# 2. 编译并安装核心模块
mvn -pl mall-core/mall-common,mall-core/mall-mbg,mall-core/mall-security install -DskipTests

# 3. 启动网关与各服务（本地开发推荐逐个启动）
mvn -pl ai-gateway spring-boot:run
mvn -pl mall-admin spring-boot:run
mvn -pl agent-customer spring-boot:run
# ... 其余模块同理

# 4. 运行测试
mvn test   # 默认跳过需要 Docker 的 benchmark 分组（-Dgroups=benchmark 可手动触发）
```

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

网关路由（无注册中心，直连地址可用 `GATEWAY_ROUTE_*` 环境变量覆盖）：

| 网关路径 | 上游服务 |
|---------|---------|
| `/admin/**` | mall-admin (8081) |
| `/api/**` | mall-portal (8087) |
| `/search/**` | mall-search (8082) |
| `/agent/customer/**` | agent-customer (8083) |

### 基础设施端口

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL | 3306 | 账号 root/root（可环境变量覆盖） |
| Redis | 6379 | 本地开发无密码 |
| RabbitMQ | 5672 / 15672 | 账号 mall/mall，vhost /mall |
| Kafka | 9092 | - |
| MongoDB | 27017 | - |
| Milvus | 19530 | 向量数据库 |
| Neo4j | 7474 / 7687 | 账号 neo4j/neo4j123 |
| Elasticsearch | 9200 | 本地开发关闭安全认证 |
| MinIO | 9000 / 9001 | minioadmin/minioadmin |

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

- 各 Agent 调用大模型需要配置 `MIMO_API_KEY`（或 `OPENAI_API_KEY`）环境变量，未配置时相应链路降级/不可用
- 智能客服的知识库（Milvus/Redis 索引）需要先通过文档导入接口写入数据，索引为空时 RAG 会直接拒答
- 智能运维 Agent 中 85% 误报率下降等指标基于内置合成演示数据（源码内已明确标注），非真实生产回放
- 本地启动的数据库凭据与中间件以 `docker-compose.yml` 与各模块 `application-dev.yml` 为准
