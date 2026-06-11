# AI-Mall：AI 增强电商系统

基于 Spring Boot 3.5 + JDK 21 的 AI 增强电商系统，集成智能客服、智能运维、自动化测试三大 AI Agent 能力，支持小米 MiMo 大模型接入。

**覆盖四个面试方向：后端工程师 / 测试工程师 / Agent 工程师 / AI 应用开发**

## 项目亮点

- **完整电商系统**：基于 mall 项目，包含商品管理、订单管理、用户管理等核心功能
- **三大 AI Agent**：
  - 智能客服 Agent（RAG + ReAct 架构）
  - 智能运维 Agent（多 Agent 协作）
  - 自动化测试 Agent（JUnit 5 + Testcontainers）
- **国产技术栈**：小米 MiMo 大模型 + Milvus 向量数据库 + Neo4j 知识图谱
- **面试全覆盖**：后端 / 测试 / Agent / AI应用 四个方向的简历、STAR 话术、八股文
- **RAG 全链路**：文档解析 → 分块 → 向量化 → 检索 → 重排序 → 生成
- **Agent 架构**：ReAct + 多Agent协作 + 事件驱动 + 记忆系统

## 四个面试方向

| 方向 | 核心技术 | 差异化卖点 |
|------|----------|-----------|
| **后端工程师** | Spring Boot + MyBatis + Redis + Kafka + ES | 多模块架构 + 缓存优化 + 消息队列 |
| **测试工程师** | JUnit 5 + Mockito + Testcontainers | 自动化测试 Agent + 覆盖度分析 |
| **Agent 工程师** | ReAct + RAG + 多Agent + 知识图谱 | Agent 架构设计 + 事件驱动 |
| **AI 应用开发** | 大模型 + 向量检索 + Prompt Engineering | RAG 全链路 + 模型路由 + 熔断器 |

## 技术栈

| 层次 | 技术 | 版本 |
|------|------|------|
| **语言** | Java | 21 |
| **核心框架** | Spring Boot | 3.5 |
| **ORM** | MyBatis Plus | 3.5.7 |
| **安全** | Spring Security + JWT | - |
| **搜索** | Elasticsearch | 7.17 |
| **缓存** | Redis | 7.x |
| **消息队列** | Kafka | 3.x |
| **向量数据库** | Milvus | 2.4 |
| **知识图谱** | Neo4j | 5.x |
| **AI 框架** | Spring AI | 1.0 |
| **大模型** | 小米 MiMo + OpenAI GPT-4 | - |
| **测试** | JUnit 5 + Mockito + Testcontainers | - |

## 项目结构

```
ai-mall/
├── pom.xml                          ← 父 POM
├── mall-core/                       ← 电商核心
│   ├── mall-common/                 ← 工具类、通用组件
│   ├── mall-mbg/                    ← MyBatis Generator 生成代码
│   ├── mall-security/               ← Spring Security + JWT
│   ├── mall-admin/                  ← 后台管理系统 API
│   ├── mall-portal/                 ← 前台商城 API
│   ├── mall-search/                 ← Elasticsearch 商品搜索
│   └── mall-demo/                   ← 测试代码
├── ai-gateway/                      ← 统一 API 入口
├── agent-customer/                  ← 智能客服 Agent
├── agent-ops/                       ← 智能运维 Agent
├── agent-test/                      ← 自动化测试 Agent
├── infra/                           ← 基础设施配置
└── docs/                            ← 面试材料
```

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+
- Docker 24+
- Docker Compose v2+

### 启动步骤

```bash
# 1. 克隆项目
git clone <repo-url>
cd ai-mall

# 2. 启动所有基础设施
docker-compose -f infra/docker-compose.yml up -d

# 3. 编译项目
mvn clean package -DskipTests

# 4. 启动应用
java -jar ai-gateway/target/ai-gateway.jar
```

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| ai-gateway | 8080 | 统一入口 |
| mall-admin | 8081 | 后台管理系统 |
| mall-portal | 8082 | 前台商城 |
| agent-customer | 8083 | 智能客服 |
| agent-ops | 8084 | 智能运维 |
| agent-test | 8085 | 自动化测试 |

### 基础设施端口

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL | 3306 | 数据库 |
| Redis | 6379 | 缓存 |
| Kafka | 9092 | 消息队列 |
| Neo4j | 7474/7687 | 知识图谱 |
| Milvus | 19530 | 向量数据库 |
| Elasticsearch | 9200 | 搜索引擎 |
| Prometheus | 9090 | 监控 |
| Grafana | 3000 | 监控可视化 |

## API 文档

启动后访问 http://localhost:8080/swagger-ui.html 查看 API 文档。

## 面试材料

详见 `docs/` 目录：

- `docs/01-简历模板/` - 四版简历（后端/测试/Agent/AI应用）
- `docs/02-STAR话术/` - 四个方向的面试话术（1min + 3min + 追问）
- `docs/03-八股文/` - 140+ 道高频面试题
- `docs/04-面试Q&A/` - 100+ 道追问应对
- `docs/05-架构文档/` - 系统设计 + 技术选型决策

## 许可证

MIT License
