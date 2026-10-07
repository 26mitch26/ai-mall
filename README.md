# AI-Mall：AI 增强电商系统

岗位作品主线：**电商客服的任务设计、业务可靠性、Agent 工程与测试闭环**。产品经理、Java 后端、AI 应用开发、测试开发分别见 [四岗位证据手册](docs/career/role-playbook.md) 和 [四岗位简历段落](docs/career/resume-project-variants.md)。确认办理为原型能力，完整真实交易及人工接收尚待验收。
需求假设、MVP 取舍、业务指标与灰度计划见 [产品方案](docs/product/ai-pm-product-brief.md)；
公开数据来源、评测方法与实际运行记录见 [Agent 产品评测](eval/README.md)。
本项目为研发原型，离线召回与程序检查成绩不代表线上用户解决率。

2026-10-07 新增 [测试报告基线对比](docs/career/report-comparison-acceptance.md)：受保护 API 与后台测试中心按稳定用例身份展示新增失败、已修复、持续失败和用例增删，并对环境未验证、基线失效与匹配歧义明确提示。运行 `python scripts/verify-career-readiness.py` 可复现本轮离线工程验收；[当前记录](eval/results/career-readiness/validation.json)与历史模型/检索评测分开统计。求职材料通过同步脚本交付到 `E:\选修课\雪\简历`。

知识库写入需要 `KNOWLEDGE_ADMIN_TOKEN` 和 `X-Knowledge-Admin-Token` 请求头，未配置时默认禁止写入。

基于 **Spring Boot 3.5 + JDK 21** 的 AI 增强电商系统，集成智能客服、智能运维、自动化测试三大 AI Agent 能力。大模型**默认运行在本地 Ollama**（生成用 `qwen3.5-noVL`、向量化用 `bge-m3`，零外网、零 API Key），亦可切换到小米 MiMo / OpenAI 兼容云服务。

## 项目亮点

智能客服新增自适应检索、可选真实神经重排、政策版本与历史引用、Redis 可恢复确认工作流、
数据库业务幂等、MCP 工具入口、照片辅助售后和 Micrometer/OpenTelemetry 追踪。
会员端启动和页面入口见下文。面试讲稿在 [会员客服面试与答辩笔记](docs/member-agent-interview.md)，Agent 架构与追问见 [升级手册](docs/agent-upgrade-guide.md)，实验方法、实测范围及后续验收见 [验收记录](docs/agent-upgrade-validation.md)。神经重排默认关闭，启用收益由本机评测决定。

- **完整电商系统**：基于 mall 项目改造，包含商品、订单、用户等核心领域模块
- **三大 AI Agent**：
  - 智能客服 Agent（RAG + ReAct 架构，含输入净化 / 输出护栏 / 工具鉴权 / 审计闭环）
  - 智能运维 Agent（多 Agent 协作 + 事件驱动 + 3-Sigma/EWMA 双算法异常检测）
  - 自动化测试 Agent（OpenAPI 发现 + Spring AI 生成用例）
- **本地优先、可上云**：默认本地 Ollama（qwen3.5-noVL 生成 + bge-m3 向量化，免外网免 Key），可选接入小米 MiMo / OpenAI 兼容云模型；向量库 Milvus + 知识图谱 Neo4j
- **会员客服 RAG**：根据问题选择语义检索、精确词项检索或混合召回；需要双路召回时以 RRF 融合，使用版本化来源约束引用。神经重排为可选，默认关闭。
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

### 启动步骤（Windows / PowerShell）

```powershell
# 在仓库根目录打开 PowerShell。首次新建可覆盖的演示库时才运行初始化：
.\scripts\init-local-db.ps1

# 首次启动或已存在 mall 数据库时，启动程序会安全提示输入本机 MySQL 密码；
# 它会启动业务服务、会员 H5、后台前端以及课设客服所需的 Docker 中间件。
.\scripts\start-demo.ps1

# 演示结束后停止本地 Java 和前端进程；Docker 中间件继续运行并保留原数据卷。
.\scripts\stop-demo.ps1
```

初始化脚本只用于准备新的演示数据库。检测到已有 `mall` 数据库时会默认停止，避免覆写数据；已有数据库直接运行启动脚本即可，不要将 `-ResetDemoData` 当作日常启动步骤。

启动后，后台运营端访问 `http://localhost:5173`，会员商城端访问
`http://localhost:5174/#/pages/public/login`。会员首页是主要演示入口；宽屏可从顶部导航进入智能客服，手机使用商城首页/原生 tabBar。会员演示账号为 `demo / Demo@123`；`admin / Admin@123` 用于管理后台，不是会员登录入口。它们仅用于本地课设环境，部署环境必须替换。

### 会员登录失败时先检查服务

会员登录需要网关8080、会员服务8087、Redis6379和本机MySQL3306；只有客服8083运行时，政策问答可以工作，会员登录仍不能用。会员密码与数据库连接密码属于不同账号，不能互相替代。

完整启动脚本 `start-demo.ps1` 会以安全输入方式询问数据库密码。若已经打开轻量客服界面，只需恢复会员服务，可在仓库根目录运行：

```powershell
.\scripts\start-member-secure.ps1
```

按提示输入本机数据库用户名与密码，密码不会显示或保存到文件，只传给本次启动的子进程。真实数据库密码不要写入README、SQL脚本、聊天或评测证据。服务重启后需要重新提供连接配置；无需重置数据库或会员密码。该轻量脚本不启动Mongo收藏/浏览历史、支付等完整依赖，只用于登录及基本MySQL业务读取。

首次运行前端如尚未安装依赖，在仓库根目录运行：

```powershell
npm --prefix frontend ci
npm --prefix frontend-app ci
```

客服使用本地 Ollama 的 `qwen3.5-noVL:latest` 和 `bge-m3`；确保 Ollama 已启动并已下载这两个模型。默认 Docker 基础设施为 Redis、MongoDB、Milvus（含 etcd/MinIO）和 Neo4j；RabbitMQ、Kafka、Elasticsearch 与监控栈按需开启。通过 `start-demo.ps1 -EnableRabbitMq`、`-EnableKafka`、`-EnableSearch` 或 `-EnableMonitoring` 开启对应可选服务。各服务依赖依据和已有 Docker 数据处置见 [Docker 资源说明](docs/docker-footprint.md)。

如果不使用脚本，可通过 `MALL_DB_URL`、`MALL_DB_USERNAME`、`MALL_DB_PASSWORD`
覆盖所有服务的数据库连接。默认 URL 指向本机 `mall` 库的 3306 端口。

若只是手动登录本机数据库，在PowerShell运行下面命令；`-p`会安全提示输入数据库密码，不把密码放进命令参数：

```powershell
& 'C:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe' --host=127.0.0.1 --port=3306 --user=root -p mall
```

成功后可执行 `SELECT DATABASE();` 检查当前库，输入 `exit` 退出。这里使用数据库连接密码；会员演示密码用于网页登录，两者不同。

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

会员端客服是主要演示入口。宽屏采用商城顶部导航与聊天/业务侧栏布局，手机保留单栏；支持政策版本原文、个人订单、售后草稿确认与恢复。面试准备文档入口见 [docs 导航](docs/README.md)：[项目讲述脚本](docs/interview-story.md)、[Agent 理解与选型](docs/interview-agent-understanding.md)、[传统后端专项](docs/interview-backend.md)、[客服](docs/interview-customer-agent.md) / [测试](docs/interview-test-agent.md) / [运维](docs/interview-ops-agent.md) 三个专项、[综合问答与"不能说的话"](docs/interview-qa.md)，客服质量评测口径见 [质量评测](docs/agent-quality-evaluation.md)，基础设施取舍与清理记录见 [Docker 资源说明](docs/docker-footprint.md)。

启动前确保本机 Ollama 已运行，并具备 `qwen3.5-noVL:latest` 与 `bge-m3` 模型。

- **智能客服**：`bge-m3 → Milvus ANN` 与 Redis BM25 按问题路由召回；混合检索时以 RRF 融合、特征重排后交给本地 Ollama 生成。启动脚本按源文件内容哈希导入或更新演示政策，不会清空共享 Redis 索引。
- **智能运维**：Monitor（3-Sigma + EWMA 双算法投票）→ RCA（Neo4j 图遍历 + 朴素贝叶斯）→ Playbook 匹配 → Change Gate 风险门控，全链路事件驱动（本地同步总线，可切 Kafka），Ollama 只生成根因摘要。
  - 指标采集面：`POST /api/v1/incidents/metrics` 上报端点 + 按配置定时拉取目标服务的 `/actuator/metrics`（targets 只来自配置文件，不接受请求传入 URL，避免 SSRF）；
  - 门控闭环：需要审批的门控生成可落盘的审批单（`aiops-gate-decisions.json`），支持批准/驳回，批准后回写历史成功率；
  - 执行环节是**模拟推演**（`simulated`），不会触碰任何真实设施——本环境无真实执行器，详见 `/agent/ops/api/v1/incidents/playbooks`。
- **自动化测试**：从 OpenAPI 优先发现真实接口，异常时使用项目内置契约；安全演示模式最多执行 8 个只读 GET API，输出状态码、响应耗时、JSON/Schema 与业务语义断言。

需要展示 Ollama 扩写测试用例时，可在启动前设置 `TEST_AGENT_AI_ENABLED=true`；默认关闭是为了保证课堂现场速度与可复现性。

## 自动化测试 Agent 的四层能力

| 模块名 | 层 | 测什么 | 判定依据 |
|---|---|---|---|
| `mall-portal` / `mall-admin` | 契约层 | OpenAPI 发现 → 用例生成 → 真实 HTTP | OpenAPI 声明（唯一 oracle）+ 状态码/Schema/业务码/分页不变式/时延 |
| `agent-customer-scenarios` | 会话层 | 11 条关键路径：意图路由、工具取数、知识来源、拒答与鉴权边界 | 关键词断言 + 来源数 + 401 边界 |
| `agent-customer-quality` | **质量评测层** | 149 条 gold 评测集（类别轮询取样） | 来源命中 / 拒答正确 / 注入阻断 / 动作一致性；开启本地模型后追加答案正确性、忠实性打分 |
| 全部 | 洞察层 | 失败回流为"已知缺陷"，报告中标注第 N 次复现 | 语义断言失败与 5xx 属高价值信号，连接噪音不沉淀 |

配套的三项工程能力：

- **环境探针**：开跑前先探 `/actuator/health`，报告头部显式标注"环境可达/不可达"，把"服务没起来"与"代码坏了"分开（实测被测未启动时 14 条全红，现在报告会自证是环境问题）。
- **被测令牌注入**：`test.agent.target-auth.token` 让契约用例带上真实登录态，此前需要鉴权的接口只会得到 401/403 的假红灯；写路径用例现在也会真正下发 `requestBody`，query 参数统一 URL 编码。
- **可被 Agent 调用**：提供 MCP 端点（见下节），CodeBuddy 等外部 Agent 可把回归当工具调用。

质量评测的方法论与诚实声明见 [客服 Agent 质量评测](docs/agent-quality-evaluation.md)。

## 自动化测试 Agent 的接入方式（MCP）

自动化测试 Agent（`agent-test`，8085）同时提供三种触发方式：

| 方式 | 端点 | 用途 |
|---|---|---|
| 前端页面 | `POST /agent/test/api/v1/test/generate?module=` | 运营后台手工发起回归 |
| REST 直调 | `POST http://localhost:8085/api/v1/test/generate?module=` | CI / 脚本 |
| **MCP** | `POST http://localhost:8085/mcp`（Streamable HTTP JSON-only，协议版本 `2025-06-18`） | 让 CodeBuddy 等外部 Agent 把回归当成工具调用 |

MCP 暴露 5 个工具，全部返回 JSON 文本，且默认"先给摘要、按需再展开"以控制上下文体积：

| 工具 | 参数 | 说明 |
|---|---|---|
| `list_test_modules` | — | 可测模块、base URL、契约/场景两类、safe-demo 与 AI 扩写开关 |
| `run_tests` | `module`、`waitSeconds`(0~300) | 提交一轮回归，**立即返回 `runId`**（一轮可能跑几十秒到几分钟，不适合同步阻塞 Agent） |
| `get_run_status` | `runId` | 运行状态与完成后的 `reportId` |
| `get_test_report` | `reportId`、`includeResults`、`maxResults` | 默认返回统计 + 失败用例 + 失败断言，`includeResults=true` 才给全部明细 |
| `list_test_reports` | `limit` | 发现历史 `reportId`，不必先跑一次 |

典型调用节奏：`list_test_modules` → `run_tests`（拿 `runId`）→ 轮询 `get_run_status` → `get_test_report` 读失败原因。**跑出红灯是正常结果，不是工具错误**（`isError=false`，用 `failedTests` 判断）。

### 报告留存

`test-reports.json`（相对工作目录，默认保留 20 份，超出淘汰最旧）保存**精简视图**：每条用例的响应体截断到 2000 字符，完整响应只留在内存。这样 `reportId` 在重启后仍可查询——MCP 侧拿到 ID 再取报告、前端历史报告列表都依赖它；文件损坏时只丢历史、不阻断启动。

### 鉴权（fail-closed）

`/api/v1/test/**` 与 `/mcp` 共用同一套入口校验，由 `TestAccessInterceptor` + `TestAccessGuard` 实施：

- **用户 JWT**：`Authorization: Bearer <token>`，与网关 `jwt.secret` 同密钥本地验签。直连 8085 时网关被绕过，由本服务兜底校验；经网关时前端/CI 走这条。
- **内部静态令牌**：启动前设置 `TEST_AGENT_TOKEN=...`，请求带 `X-Test-Agent-Token: <token>`（也支持 `Authorization: Bearer <token>`）。供 CodeBuddy 等无用户身份的客户端与 CI 使用。
- 校验失败一律 401，**没有匿名放行分支**；`test.agent.auth.enabled=false` 仅供本地调试，启动日志会打 WARN。
- `module` 走白名单（`test.agent.modules`），任意字符串会被 400 拒绝——否则本服务会变成"对内网系统的自动化扫描器"。

MCP 端点**不进网关白名单**（与客服侧 `/agent/customer/mcp` 相反）：客服侧最坏是读到他人订单，测试侧最坏是以服务身份对内网发任意请求，因此协议入口即要求凭证。并发默认 1、队列 8，队列满直接返回 `isError`，避免被当作压测入口。

```bash
# 1) 设置内部令牌并启动
$env:TEST_AGENT_TOKEN = "change-me-in-prod"
# 2) 握手
curl -X POST http://localhost:8085/mcp -H "Content-Type: application/json" `
  -H "Accept: application/json, text/event-stream" -H "MCP-Protocol-Version: 2025-06-18" `
  -H "X-Test-Agent-Token: change-me-in-prod" `
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
# 3) 发起回归（后续调用都要带 MCP-Protocol-Version 头）
curl -X POST http://localhost:8085/mcp -H "Content-Type: application/json" `
  -H "Accept: application/json, text/event-stream" -H "MCP-Protocol-Version: 2025-06-18" `
  -H "X-Test-Agent-Token: change-me-in-prod" `
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_tests","arguments":{"module":"mall-portal"}}}'
```

在 CodeBuddy / Claude Code 等 MCP 客户端中登记（`<项目根>/.mcp.json`）：

```json
{
  "mcpServers": {
    "ai-mall-test-agent": {
      "type": "http",
      "url": "http://localhost:8085/mcp",
      "headers": { "X-Test-Agent-Token": "change-me-in-prod" }
    }
  }
}
```

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

## 最新文档与分块验证

课程提交以 [实验报告最终版](实验报告-最终版.md) 为入口；任务与资源实证见 [产品评测报告](eval/product-evaluation-report.md)。新导入推荐政策章节与例外保护policy_section，当前共享知识未重建；8/8是开发夹具结构完整性，不是回答准确率。详细取舍见 [Chunk审查](docs/product/chunk-strategy-review.md)，文件同步与范围见 [文档核验清单](docs/product/document-sync-audit.md)。
