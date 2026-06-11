# AI-Mall：AI 增强电商系统

基于 **Spring Boot 3.5 + JDK 21** 的 AI 增强电商系统，集成智能客服、智能运维、自动化测试三大 AI Agent 能力，支持小米 MiMo 大模型接入。

**覆盖四个面试方向：后端工程师 / 测试工程师 / Agent 工程师 / AI 应用开发**

## 项目亮点

- **完整电商系统**：基于 mall 项目，包含商品管理、订单管理、用户管理等核心功能
- **三大 AI Agent**：
  - 智能客服 Agent（RAG + ReAct 架构）
  - 智能运维 Agent（多 Agent 协作）
  - 自动化测试 Agent（OpenAPI 发现 + Spring AI 智能生成）
- **国产技术栈**：小米 MiMo 大模型 + Milvus 向量数据库 + Neo4j 知识图谱
- **面试全覆盖**：后端 / 测试 / Agent / AI应用 四个方向的简历、STAR 话术、八股文
- **RAG 全链路**：文档解析 → 分块 → 向量化 → 检索 → 重排序 → 生成
- **Agent 架构**：ReAct + 多Agent协作 + 事件驱动 + 记忆系统
- **CI/CD 就绪**：GitHub Actions + Docker 多阶段构建 + Kubernetes 部署

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
├── mall-core/                       ← 电商核心（7个子模块）
│   ├── mall-common/                 ← 工具类、通用组件、全局异常处理
│   ├── mall-mbg/                    ← MyBatis Generator 生成代码
│   ├── mall-security/               ← Spring Security + JWT + 动态权限
│   ├── mall-admin/                  ← 后台管理系统 API
│   ├── mall-portal/                 ← 前台商城 API
│   └── mall-search/                 ← Elasticsearch 商品搜索
├── ai-gateway/                      ← Spring Cloud Gateway 统一入口
├── agent-customer/                  ← 智能客服 Agent（ReAct + RAG）
├── agent-ops/                       ← 智能运维 Agent（事件总线）
├── agent-test/                      ← 自动化测试 Agent（智能生成）
├── infra/                           ← 基础设施配置
│   ├── docker-compose.yml           ← Docker Compose 开发环境
│   ├── docker-compose.prod.yml      ← Docker Compose 生产环境
│   ├── prometheus/                  ← Prometheus 监控配置
│   └── k8s/                         ← Kubernetes 部署配置
├── .github/workflows/               ← CI/CD 流水线
│   ├── ci.yml                       ← 持续集成（编译+测试+覆盖率）
│   └── cd.yml                       ← 持续部署（Docker + K8s）
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
git clone https://github.com/26mitch26/ai-mall.git
cd ai-mall

# 2. 启动所有基础设施（MySQL/Redis/Kafka/Milvus/Neo4j/ES等）
docker-compose -f infra/docker-compose.yml up -d

# 3. 编译项目
mvn clean package -DskipTests

# 4. 启动网关（统一入口）
java -jar ai-gateway/target/ai-gateway.jar

# 或者启动所有服务（推荐使用 docker-compose.prod.yml）
docker-compose -f infra/docker-compose.prod.yml up -d
```

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| ai-gateway | 8080 | 统一入口（推荐通过网关访问） |
| mall-admin | 8081 | 后台管理系统 |
| mall-portal | 8082 | 前台商城 |
| mall-search | 8083 | 商品搜索 |
| agent-customer | 8084 | 智能客服 |
| agent-ops | 8085 | 智能运维 |
| agent-test | 8086 | 自动化测试 |

### 基础设施端口

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL | 3306 | 数据库 |
| Redis | 6379 | 缓存 |
| Kafka | 9092 | 消息队列 |
| Neo4j | 7474/7687 | 知识图谱（HTTP/Bolt） |
| Milvus | 19530 | 向量数据库 |
| Elasticsearch | 9200 | 搜索引擎 |
| Prometheus | 9090 | 监控 |
| Grafana | 3000 | 监控可视化 |

## API 文档

启动后访问以下地址查看 API 文档：

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/api-docs

## CI/CD 流水线

### 持续集成 (CI)

触发条件：`push` 到 `main` 分支 或 `pull request`

流程：
1. 检出代码 + 设置 Java 21
2. Maven 编译（启用缓存）
3. 运行单元测试（全8模块）
4. 生成 JaCoCo 覆盖率报告
5. 上传测试报告到 GitHub Artifacts

### 持续部署 (CD)

触发条件：创建 `v*` 标签

流程：
1. 构建 7 个 Docker 镜像（多阶段构建）
2. 推送到 Docker 镜像仓库
3. 部署到 Kubernetes 集群
4. 验证 Rollout 状态
5. 发送部署通知

## Kubernetes 部署

```bash
# 创建命名空间
kubectl create namespace ai-mall

# 创建 Secret（数据库密码等敏感信息）
kubectl create secret generic db-credentials \
  --from-literal=mysql-password=xxx \
  --from-literal=redis-password=xxx \
  --from-literal=jwt-secret=xxx

# 部署基础设施
kubectl apply -f infra/k8s/infra-deployment.yaml

# 部署应用服务
kubectl apply -f infra/k8s/

# 查看部署状态
kubectl get pods -n ai-mall
```

## 测试覆盖

| 模块 | 测试类型 | 测试类 | 测试用例 |
|------|----------|--------|----------|
| mall-common | 单元测试 | 3 | 42 |
| mall-security | 单元测试 | 2 | 18 |
| mall-admin | 集成测试 | 1 | 8 |
| mall-portal | 单元测试 | 1 | 15 |
| ai-gateway | 单元测试 | 1 | 7 |
| agent-customer | 单元测试 | 3 | 25 |
| agent-ops | 单元测试 | 2 | 18 |
| **总计** | | **13** | **~133** |

**测试特性**：
- ✅ JUnit 5 + Mockito
- ✅ Testcontainers（真实数据库集成）
- ✅ 参数化测试
- ✅ 并发测试
- ✅ 无 Thread.sleep（全部改用轮询）

## 面试材料

详见 `docs/` 目录：

- `docs/01-简历模板/` - 四版简历（后端/测试/Agent/AI应用）
- `docs/02-STAR话术/` - 四个方向的面试话术（1min + 3min + 追问）
- `docs/03-八股文/` - 140+ 道高频面试题
- `docs/04-面试Q&A/` - 100+ 道追问应对
- `docs/05-架构文档/` - 系统设计 + 技术选型决策

## 许可证

MIT License

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=26mitch26/ai-mall&type=Date)](https://star-history.com/#26mitch26/ai-mall&Date)