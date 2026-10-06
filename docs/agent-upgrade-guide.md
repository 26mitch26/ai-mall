# 智能客服升级运行与答辩手册

> **2026-10-07核验：** 本文保留早期工程/选型阶段的记录，未重测的数值不是当前结果。当前任务评测、模型入口、分块与登录状态以 [产品评测报告](../eval/product-evaluation-report.md)、[最终实验报告](../实验报告-最终版.md) 和 [Chunk策略审查](product/chunk-strategy-review.md) 为准。旧子块实验不否定完整父段恢复；历史来源命中与测试通过不能当作真实解决率。


本手册描述当前代码能够复现的行为。它不报告未经本机实测的延迟、准确率或成本数据。

## 本地运行前检查

运行环境使用 Java 21、Spring Boot 3.5、Spring AI 1.0、Redis、Milvus、Neo4j（可选）以及本机 Ollama。当前客服对话默认通过 `AGENT_LLM_BASE_URL` 调用本地 Ollama `/api/chat`，聊天模型默认 `qwen3.5-noVL:latest`；embedding 默认 `bge-m3`。照片观察默认调用本机 Ollama 的 `qwen3-vl:latest`。本地模型已安装时不需要云模型密钥。

可用仓库启动脚本启动服务：

```powershell
.\scripts\start-demo.ps1
```

该脚本会启动本地基础设施、构建并启动多个服务，还可能提示输入本机数据库密码。它不是只读脚本；只在准备好的本机开发环境运行。后台页面默认在 `http://localhost:5173`，网关默认在 `http://localhost:8080`。依赖、端口和网关路由见根目录 [README](../README.md) 与 [start-demo.ps1](../scripts/start-demo.ps1)。

脚本也会启动会员商城 H5，地址为 `http://localhost:5174/#/pages/public/login`。先用 `npm --prefix frontend ci` 和 `npm --prefix frontend-app ci` 安装两套前端依赖。本机 MySQL 已有 `mall` 数据库时直接运行演示脚本；`init-local-db.ps1` 只用于新演示数据库，发现已有数据库会停止以避免覆盖。演示完成后 `stop-demo.ps1` 停止由脚本启动的 Java/前端进程，基础设施容器和数据卷保留。

Neo4j 与神经重排默认可关闭。要在本机启用这两个可选组件，先启动 Neo4j 和 reranker，然后在启动 agent-customer 的同一进程环境设置：

```powershell
$env:AI_RAG_RERANKER_ENABLED = 'true'
$env:AI_RAG_GRAPH_ENABLED = 'true'
$env:AI_RAG_GRAPH_URI = 'bolt://localhost:7687'
$env:AI_RAG_GRAPH_USERNAME = 'neo4j'
$env:AI_RAG_GRAPH_PASSWORD = '<从本地密钥管理器取得>'
```

当前机器的 reranker 权重放在 `.run/reranker-model`。服务入口和模型路径检查见 [scripts/rag-reranker/README.md](../scripts/rag-reranker/README.md) 与 [server.py](../scripts/rag-reranker/server.py)。该服务监听 `127.0.0.1:8092`；Java 客户端超时、返回格式错误或服务不可用时会回退到特征重排。不要把图数据库密码提交到仓库或写入演示截图。

先核对 `http://localhost:8080/agent/customer/api/v1/knowledge/status` 返回的服务状态与已索引文档数量。前端知识导入使用原有后台管理员认证；会员 JWT 不会替代管理员凭证。知识文件可从 [docs/knowledge](knowledge) 选取，导入前核对它们是否适合当前演示环境。

## 默认验证与可选草稿验证

默认验证只读取公开状态、执行 MCP 握手、列出匿名可见工具并调用公开商品搜索：

```powershell
.\scripts\verify-agent-upgrade.ps1
```

默认网关地址是 `http://localhost:8080/agent/customer`，可用 `-BaseUrl` 指向本机其他地址。脚本不带管理员或会员凭证，不下单、不取消订单、不创建售后申请，也不确认退款/退货。MCP 工具调用仍会按现有审计设计写入客服审计流水；“只读”指不更改商城业务订单或售后状态。

可选的 `-PrepareWorkflow` 会要求隐藏输入一枚有效会员 JWT，并提示订单号与申请理由。它会核对该会员订单、检索政策并创建 Redis 中的售后草稿；脚本**不会发送确认请求**。这会产生持久工作流状态，所以只在本地隔离环境和专用测试会员上使用：

```powershell
.\scripts\verify-agent-upgrade.ps1 -PrepareWorkflow -OrderSn '<测试会员自己的订单号>' -Reason '商品外观破损'
```

准备工作流本身不调用商城售后写接口。工作流的确认按钮才会触发真实写入；不能在共享或生产商城数据上测试确认。脚本不会执行 SQL，也没有确认售后的选项。实现见 [AfterSaleWorkflowService](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/workflow/AfterSaleWorkflowService.java) 和 [verify-agent-upgrade.ps1](../scripts/verify-agent-upgrade.ps1)。

工作流确认依赖商城库中的幂等操作表。商城 `dev` 配置会在启动时执行 `CREATE TABLE IF NOT EXISTS`，仅创建这一张新表，不改已有订单或重置数据；可用 `AGENT_OPERATIONS_SCHEMA_INIT=false` 关闭。生产配置不自动建表，按目标环境流程执行 [scripts/sql/2026-10-agent-idempotency.sql](../scripts/sql/2026-10-agent-idempotency.sql)。SQL 的资源副本在 `mall-portal/src/main/resources/db/agent-operation-idempotency.sql`，两份须同步维护。

## 五个演示场景

1. **会员端入口**：访问会员 H5，在首页通过导航打开“智能客服”或“帮助中心”；PC 用顶部导航和业务侧栏，手机用单栏与首页 tabBar。
2. **可追溯的政策问答**：在知识库已导入退货/配送政策的前提下，询问“签收后多久可以申请退货？”。展示答复引用的来源、版本和原文。若知识状态显示没有文档，不要把空知识库的拒答包装成 RAG 成功。
3. **会员身份隔离与商品数据**：登录演示会员，查询其个人订单，再询问在售手机。订单绑定经 `/sso/info` 核验出的会员身份；商品价格/库存来自商城 API。请求体中的 `userId`、`memberId` 不是认证凭据；管理员 token 不能当会员 token 使用。
4. **检索路径与证据边界**：询问“退款和运费规则如何一起计算？”。调试详情可查看实际 route、阶段耗时、模型调用和 token 数。若有可用图路径可展开“政策主题关联”；图路径为空或服务关闭时，不展示关系。证据线索不代表语义蕴含或事实正确。
5. **照片辅助与售后人工确认**：隔离环境中上传符合服务限制的商品照片，查看观察和建议理由，再生成售后草稿，核对订单、理由与政策，选择拒绝或确认。照片模型不判定责任或退款资格；遇到 `UNKNOWN` 先查询状态，不盲目重试。商城业务写入只在专用隔离数据上确认。

## 当前能力边界

### MCP

MCP endpoint 是网关 `/agent/customer/mcp`（直连 agent-customer 时 `/mcp`）。它实现固定 **2025-06-18、无状态、JSON-only Streamable HTTP profile**：`initialize`、`ping`、`notifications/initialized`、`tools/list`、`tools/call`。POST 要求 `Content-Type: application/json` 和同时接受 `application/json, text/event-stream`；初始化协商后，后续请求必须带 `MCP-Protocol-Version: 2025-06-18`。匿名工具为 `search_products`；核验身份后另提供 `get_order_info` 和 `prepare_after_sale`。

这个 profile 不提供 GET SSE stream（GET 返回 405）、session ID、断线续传、resources、prompts、sampling，也不支持其他协议版本。它不应被描述成支持完整 MCP 最新版。Spring AI 1.0 的 server starter 文档列出的是 STDIO 与旧 HTTP+SSE 传输；内建 Streamable HTTP 在后续 Spring AI 版本中才提供，因此当前工程没有升级 Spring AI 或 Boot，也没有把轻量 profile 说成官方 SDK 的完整实现。协议行为依据 [MCP 2025-06-18 Streamable HTTP 规范](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)；Spring AI 兼容范围依据 [Spring AI 1.0 MCP Server 文档](https://docs.spring.io/spring-ai/reference/1.0/api/mcp/mcp-server-boot-starter-docs.html)。协议入口和限制见 [McpController](../agent-customer/src/main/java/com/ai/mall/agent/customer/controller/McpController.java)、[McpProtocolService](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/mcp/McpProtocolService.java)、[MCP HTTP tests](../agent-customer/src/test/java/com/ai/mall/agent/customer/controller/McpControllerTest.java)。

带 `Origin` 的浏览器请求必须与精确配置的 `agent.mcp.allowed-origins` 匹配；默认空值会拒绝所有带 `Origin` 的请求，部署时按受信页面源设置。MCP 端点在 HTTP Security 层允许匿名访问，身份与权限在每个工具调用内单独执行。白名单不暴露直接下单、取消订单或直接提交售后工具。

### 售后工作流、图与证据报告

- 售后流程是本项目自建的 Redis 持久状态机，草稿 TTL 为 7 天，按会员 owner、状态和版本做检查，并用幂等表协调真实商城写入。它**不是已经集成 Spring AI Alibaba 的 workflow/agent framework**。相关 API 与实现见 [WorkflowController](../agent-customer/src/main/java/com/ai/mall/agent/customer/controller/WorkflowController.java) 和 [AfterSaleWorkflowService](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/workflow/AfterSaleWorkflowService.java)。
- `PolicyGraphService` 不是完整 Microsoft GraphRAG。它仅对一组固定政策主题做共享主题关联，最多扫描 200 个公开来源、返回最多 8 条路径，并在证据再取阶段最多保留 4 份来源；少于两个主题时不查图。Neo4j 关联用于扩展局部召回，政策正文仍是证据。实现见 [PolicyGraphService](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/graph/PolicyGraphService.java)。
- `evidenceReport` 当前通过词面片段和指定数字单位匹配引用；冲突检测也只覆盖积分抵扣上限这一类规则。`lexical-match` 不等于蕴含，`unsupportedNumericClaims` 不是所有幻觉的数量，更不等于语义 faithfulness 或事实准确率。实现见 [EvidenceVerifier](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/evidence/EvidenceVerifier.java)。
- 照片检查默认调用本机 Qwen3-VL；上传限制为 2 MiB、解码尺寸最多 8 MP、送模前缩放到最长边 1024 像素，生成预算为 240 token。返回观察列表、建议理由和 `requiresReview=true`；不是图像鉴定、损坏责任认定或售后资格判定。实现见 [AfterSaleVisionService](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/vision/AfterSaleVisionService.java) 和 [VisionController](../agent-customer/src/main/java/com/ai/mall/agent/customer/controller/VisionController.java)。

## 指标读法与预算控制

| 字段/限制 | 定义 | 不应作出的推断 |
|---|---|---|
| `responseTime` | ChatService 从处理本轮开始到 `reactAgent.think` 返回的毫秒数；后续证据报告构建和 HTTP 序列化不在其中 | 不等于完整端到端网络延迟 |
| `trace.stages[]` | 代码显式记录的阶段耗时和 outcome；不同路径记录的阶段不同，个别占位阶段可为 0 | 阶段之和不保证等于 `responseTime` |
| `trace.modelCalls` / token 字段 | 当前 trace scope 记录到的模型调用次数及 Ollama `prompt_eval_count` / `eval_count` 累计值；模型没有返回 usage 时 token 数可能不完整 | 不是云厂商账单 token，也不是全进程流量 |
| `correctionCount` | 本轮查询词扩展或多意图拆解是否执行（计数 0/1） | 不是答案被纠正次数或成功率 |
| `evidenceScore` | 检索候选的证据强度信号 | 不是答案正确概率或 faithfulness 分数 |
| 语义缓存 | 每实例内最多 300 条、24 小时 TTL，语义匹配阈值 0.92，并以知识 epoch 使旧版本缓存失效 | 指标不会跨进程重启累计，也不保证不同知识语料都适合 0.92 |
| Chat/ReAct 模型预算 | ReAct 每轮最多 5 次迭代；ChatService telemetry scope 最多预留 6 次模型调用；自定义 Ollama Chat API 默认 `max-tokens=512` | 不涵盖每个独立工作流/照片端点，不能据此承诺固定延迟 |
| 可选 reranker | 本机 Qwen3-Reranker-0.6B 调用 timeout 默认 2 秒，失败回退特征重排 | 启用配置不代表每次请求都由神经模型完成重排 |

默认路径使用本机 Ollama、Milvus、Redis 和可选本机 Neo4j/reranker，不需付费模型 API。实际计算仍消耗本机 CPU/GPU、内存和电力。演示时先检查模型与依赖服务在线，预热模型，保持问句简短；慢 reranker 有 2 秒回退，前端聊天/task/workflow/vision 请求使用较长 HTTP timeout，但这不替代后端预算或 deadline。不要把远程付费模型端点配置成默认演示路径。

本机的构造样本评测、真实服务验证和选型取舍见 [验收记录](agent-upgrade-validation.md)。原始报告保留了每个问题的响应和 trace；来源命中率不能等同于回答正确率，单轮顺序测试也不能外推为稳定云延迟。

## 常见答辩追问

**为什么不只用 Milvus ANN？** ANN 擅长语义相似召回；BM25 补精确词项与标识符，RRF 融合后再重排。答复源卡保留版本和内容哈希，便于复核原文。

**系统如何避免幻觉？** 检索不足可拒答，工具有访问分级，订单查询透传已核验会员 token，输出经过护栏，证据报告列出可核对的匹配线索。它仍不具备通用蕴含验证；人工应检查原文，尤其金额、时限和政策冲突。

**这是不是 GraphRAG？** 这是 Neo4j 上按少数固定政策主题建立的局部关联，用来拓展召回。没有构建一般实体知识图、社区摘要或完整 Microsoft GraphRAG 算法。

**为什么自建 MCP 协议层？** 工程锁定 Spring AI 1.0；对应官方 starter 使用旧 SSE。为不引 Boot 4/Spring AI 2，当前只实现文档列明的 MCP 2025-06-18 JSON-only profile，并用 HTTP 与协议测试验证；其它规范能力明确未实现。

**人工确认具体保护什么？** 草稿冻结订单快照、理由和政策版本；确认携带 `taskId` 与 `expectedVersion`，状态转换用 Redis CAS，商城写入用 SQL 幂等键。同一工作流不会因为重复确认就重新盲写；若结果不确定，先走状态查询/操作查账。部署前 SQL 迁移是必需项。

## 代码入口与测试

- RAG 与可解释检索：[RagService](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/rag/RagService.java)、[ChatService](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/agent/ChatService.java)
- 会员验证与身份隔离：[MemberIdentityResolver](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/security/MemberIdentityResolver.java)、[ToolAccessGuard](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/security/ToolAccessGuard.java)
- 图、证据和遥测：[PolicyGraphService](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/graph/PolicyGraphService.java)、[EvidenceVerifier](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/evidence/EvidenceVerifier.java)、[AgentTelemetry](../agent-customer/src/main/java/com/ai/mall/agent/customer/service/telemetry/AgentTelemetry.java)
- 本次 MCP 协议与控制器测试：[McpProtocolServiceTest](../agent-customer/src/test/java/com/ai/mall/agent/customer/service/mcp/McpProtocolServiceTest.java)、[McpControllerTest](../agent-customer/src/test/java/com/ai/mall/agent/customer/controller/McpControllerTest.java)

Java 测试、两套前端构建、真实 Redis/SQL 验证及隔离服务演示已执行，具体命令、环境和边界见 [验收记录](agent-upgrade-validation.md)。本轮没有直接修改正式数据库；本机正式演示下次启动时，dev 配置会安全初始化幂等表。
