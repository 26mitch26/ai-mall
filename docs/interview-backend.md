# 二、传统后端视角的面试准备（Java / Spring / 分布式）

> **2026-10-07核验：** 本文保留早期工程/选型阶段的记录，未重测的数值不是当前结果。当前任务评测、模型入口、分块与登录状态以 [产品评测报告](../eval/product-evaluation-report.md)、[最终实验报告](../实验报告-最终版.md) 和 [Chunk策略审查](product/chunk-strategy-review.md) 为准。旧子块实验不否定完整父段恢复；历史来源命中与测试通过不能当作真实解决率。


> 这份文档面向**纯后端岗位**面试官：不谈 AI 概念，只谈后端工程。
> 每条都带本项目的真实证据（类名/行号/参数值），方便你顺着代码自己复核。
> 标 ⚠️ 的是**我主动承认的短板**，面试时最好自己先说出来。

---

## 1. 项目规模与模块划分（先讲结构，再讲细节）

Maven 多模块，父 POM 用 `spring-boot-starter-parent:3.5.0`，JDK 21，11 个模块：

```
mall-core
├── mall-common   公共能力：统一响应/异常、Redis 封装、分布式锁、模型熔断器
├── mall-mbg      MyBatis-Plus entity/mapper（mbg）
└── mall-security JWT 鉴权、Redis 缓存切面
mall-admin / mall-portal / mall-search / mall-message / mall-generator / mall-demo
ai-gateway（Spring Cloud Gateway，WebFlux）
agent-customer（8083）/ agent-ops（8084）/ agent-test（8085）
```

**依赖关系**（`cd.yml` 的 `-pl mall-core/mall-admin -am` 可印证）：
业务模块 → mall-core 三件套 → Spring Boot；`ai-gateway` 独立（WebFlux，不能和 Servlet 混）；
三个 Agent 只依赖 `mall-common`（熔断器/Redis），不碰业务库。

**讲这个的意义**：体现你知道"模块边界按依赖方向划，而不是按功能堆"——
Agent 服务不依赖业务代码，只复用公共能力，所以它们能被独立部署、独立扩容。

## 2. 统一响应与全局异常（最容易被问，也最容易答好）

- `CommonResult<T>`：字段 `code/message/data`，工厂方法
  `success(data)` / `failed(IErrorCode)` / `unauthorized(data)` / `forbidden(data)` / `validateFailed(...)`。
- 错误码体系：`IErrorCode` 接口 + `ResultCode` 枚举（业务码与 HTTP 码解耦）。
- `GlobalExceptionHandler`（`@RestControllerAdvice`）分支覆盖：
  `ApiException` / `MethodArgumentNotValidException` / `BindException` /
  `HttpRequestMethodNotSupportedException` / `MissingServletRequestParameterException` /
  `ConstraintViolationException` / `AuthenticationException` / `AccessDeniedException` /
  `NoHandlerFoundException` / **`NoResourceFoundException`（Boot 3.x 实际抛的 404）** /
  `TypeMismatchException` / 兜底 `Exception`。
- 三个 Agent 各自有局部 advice（`WorkflowExceptionAdvice` / `TestApiExceptionHandler` /
  网关 `GatewayExceptionHandler`）。

**为什么值得讲**：Boot 3 之后 404 由 `NoResourceFoundException` 抛出而不是 `NoHandlerFoundException`，
不区分会返回 500——这是真实踩过的坑（实验记录里"未知路径被兜底异常伪装成 500"就是它）。

- 参数校验：JSR-380（`@Valid` + `@Validated`）为主，**大量手写校验并存**
  （例如下单幂等键用正则 `^[A-Za-z0-9_-]+$` 且长度 ≤128）。
  评价：手写校验在"业务语义校验"上更直白，但容易漏，理想是统一到 Bean Validation + 自定义约束注解。

## 3. 分页

- 统一入参 `pageNum`（1-based）+ `pageSize`（默认 5/10），出参 `CommonPage<T>`
  （`pageNum/pageSize/totalPage/total/list`）。
- 实现：MyBatis **PageHelper**（`startPage` + `PageInfo`）；Mongo 用 Spring Data `Pageable`；ES 用 `EsProduct` Page。
- 兜底：`PmsPortalProductServiceImpl` 里对 `pageNum=0` 做了非法 offset 兜底。
- ⚠️ **深分页未处理**：仍是 `LIMIT offset, size`，没有游标/seek 方案，
  大偏移量下 MySQL 需要扫描大量行。正确做法是改写为 `WHERE id > last_id ORDER BY id LIMIT n`
  或延迟关联，取决于业务是否需要总数。

## 4. 数据库与索引

- 表按域组织在 `infra/mysql/init.sql`：`ums_*`（用户/权限）、`oms_*`（订单/购物车/优惠券）、
  `pms_*`（商品/品牌/分类）、`cms_*`、`sms_*`。
- 关系表统一用**复合唯一键**（`uk_role_menu`、`uk_admin_role`、`uk_resource_url`）而不是自增 id，
  天然防重复。
- 值得讲的索引：
  - `idx_cart_member(member_id, delete_status)` 联合索引，查询按 member 前缀；
  - `idx_coupon_member(coupon_id, member_id)`（覆盖"某券被谁用过"）；
  - `oms_order`：`idx_order_sn` 唯一 + `idx_member_id` + `idx_id`；
  - 幂等表 `uk_agent_operation_owner_key(owner_type, owner_id, operation_key)` 唯一键 + `created_at` 辅助。
- **DDL 幂等化**（`init.sql`）：`CREATE TABLE IF NOT EXISTS` + `DELETE; INSERT` 可重复执行；
  补列用存储过程 `ai_mall_add_column_if_missing` 只加列不删数据。
  价值：同一份脚本既能初始化全新环境，也能给存量环境补表补列——这是运维交付里很实用的一招。
- ⚠️ 没有使用 MySQL 乐观锁 `version` 字段（model 里的 version 都是 `serialVersionUID`）；
  乐观语义都用 Redis 侧实现（工作流 CAS + Lua）。

## 5. 事务

- `@Transactional` 集中在 `mall-portal` 的订单、购物车、会员、地址、优惠券、售后申请。
- ⚠️ **全部使用默认传播行为与默认隔离级别**（MySQL RR），没有自定义 `isolation/propagation/timeout/rollbackFor`。
  实践中更该关注的是**事务里不要放远程调用**（下单事务里没有调 LLM，这点是对的），
  以及**幂等与事务的边界**（见下节）。

## 6. 幂等：下单三段式（本项目最值得讲的后端设计）

`OmsPortalOrderController` + `scripts/sql/2026-10-agent-idempotency.sql`：

1. **唯一键兜底**：`uk_agent_operation_owner_key(owner_type, owner_id, operation_key)`，
   同一用户同一操作键只能成功插入一次；
2. **参数指纹**：SHA-256(TreeMap 规范化参数) 存 `param_hash`，
   **同 key 不同参数直接 409**，防止"用同一个 token 改参数下单"；
3. **行锁串行化**：`INSERT ... ON DUPLICATE KEY UPDATE id=id` + `SELECT ... FOR UPDATE`，
   把并发同 key 的请求排成串行；
4. **结果回放**：若首次已完成（`status=COMPLETED`），直接回放 `result_json`，
   于是**超时重试不会重复下单**；结果与业务写同事务提交；
5. 幂等键本身是 `GET /order/token` 返回的 UUID（**无服务端存储，纯客户端令牌**），
   校验只做非空/长度/正则——这个设计取舍要讲清楚：**它防的是"重复提交"，不防"伪造"**，
   防伪造要靠 `owner_id`（从登录态取，不信任请求体）。

并发验证有单测（`OmsPortalOrderControllerIdempotencyH2Test`，用 H2 跑并发场景）。
**后端幂等的完整语义（重复提交 / 参数不一致 / 并发 / 超时重试 / 跨服务）这里都覆盖了。**

## 7. Redis：七种用途 + 故障隔离

| 用途 | 结构与 key | TTL |
|---|---|---|
| BM25 倒排索引 | Set `bm25:inverted:{term}`、Hash `bm25:doc:{id}`(:tf)、String `bm25:stats:*` | 常驻 |
| 知识库版本 | ZSet `rag:revisions:{source}`、String `rag:active:{source}:{scope}`、String `rag:knowledge:epoch` | 常驻 |
| 会话短期记忆 | String(JSON) `chat:session:{sessionId}` | 24h，最多 10 条滚动截断 |
| 审计留痕 | List `agent:audit:{sessionId}`，`rightPush` + `trim(-200,-1)` | 7 天 |
| 售后工作流状态 | String(JSON) + **Lua CAS** | 7 天 |
| ReAct 思考链 | String（`setIfAbsent`） | 7 天 |
| 后台资源/权限缓存 | String `{db}:{key}:{adminId}` | 常量配置 |

- 封装在 `RedisServiceImpl`（String/Hash/Set/List + `incr/decr` + **Lua 脚本**）。
- **亮点 1：Lua 原子扣库存**。注释里写了为什么不用 `DECRBY`：会扣成负数、需要回滚、中间有窗口。
  批量版实现"**全够才全扣，一个不够全不扣**"，避免部分扣减后回滚。
- **亮点 2：缓存故障不拖垮业务**。`RedisCacheAspect` 切 `*CacheService.*`，
  异常只打日志（除非方法标 `@CacheException`）——缓存是加速器不是依赖。
  这条能引出完整的降级链（见第 11 节）。
- **一致性策略**：项目**没有**用 Spring Cache Abstraction（0 处 `@Cacheable`），
  而是 Cache-Aside + 手工失效（改权限/角色后按前缀批量 `del`）。
  ⚠️ 没有双写失效/延迟双删；语义缓存靠**知识版本 epoch 变更即全清**来保证正确性。
- **分布式锁**（`DistributedLock`）：`SET NX EX` + requestId 校验解锁（Lua，防误删）+
  **可重入**（Hash 计数）+ **看门狗续期**（`ScheduledExecutorService`，间隔 = 过期时间/3）+
  `tryLock` 自旋 50ms。没引 Redisson。⚠️ 看门狗是本地定时器，**多实例下每个实例各续一次**，
  极端情况可能续期过久；生产上更稳的做法是 Redis 侧做 fencing token 或换 Redisson。

## 8. 并发

- 线程池**没有统一治理**（⚠️ 无 `@Async`/`@EnableAsync`）：
  - `DistributedLock` 的看门狗：`newScheduledThreadPool(CPU 核数)`，daemon；
  - agent-test 的运行调度：`ThreadPoolExecutor(core=max=maxConcurrentRuns, ArrayBlockingQueue(maxQueued), AbortPolicy)`，
    队列满抛异常而不是静默排队（防被当压测入口）；
  - `ChatController` SSE：`newCachedThreadPool()`（⚠️ cached 无界，理论上有线程膨胀风险，
    生产建议换成带上限的池 + 背压）。
  - `MemoryService` 用 `CompletableFuture.runAsync` 异步落 Milvus（默认 ForkJoinPool，⚠️ 未自定义）。
- 锁：`synchronized`（语义缓存、Neo4j driver 懒初始化、指标集合、缺陷库）、
  `ReentrantLock`（熔断器状态切换 + 双重检查）、`AtomicX`（熔断器状态与计数、缓存命中统计）。
  无 `ReadWriteLock`。
- ⚠️ **一个反面案例**：`TestAgent` 声明了 `moduleLocks: ConcurrentMap<String, ReentrantLock>`
  却从未使用，注释还写着"支持多模块并发"——**声明与实现不一致**，后来新增的 MCP 调度层
  才真正把"并发受控"这件事做实（有界队列 + 拒绝 + 句柄查询）。

## 9. 限流、熔断、降级

### 9.1 网关限流（按下游成本分层，这个思路值得讲）
`ai-gateway/application.yml`，Redis `RequestRateLimiter`，Key = IP：

| 路由 | replenishRate | burstCapacity | 依据 |
|---|---|---|---|
| `mall-search` | 20 | 50 | 检索便宜、QPS 高 |
| `mall-portal` | 10 | 20 | 交易链路 |
| `agent-customer` | 5 | 10 | 内含 LLM 调用 |
| `agent-ops` | 3 | 6 | **同步调本地大模型可达 60s，不限流=廉价 DoS 面** |

`mall-admin` 与 `agent-test` **未配限流**（⚠️ agent-test 现在有自己的并发上限与鉴权，
但网关层限流缺失，建议补）。
⚠️ resilience4j 只在 yml 里声明了默认配置，**代码里 0 处注解使用**（属预留）。

### 9.2 自研模型熔断器（`ModelCircuitBreaker`）
- 三态 CLOSED / OPEN / HALF_OPEN，`ReentrantLock` + 双重检查保证状态切换原子性。
- **双路判定**：连续失败数 ≥ 阈值（默认 5）**或** 滑动窗口失败率 ≥ 0.5 且样本 ≥ 10（窗口 10s）。
  两者互补：前者快速止损，后者兜住"零星失败被误判"的情况。
- 恢复：`resetTimeoutMs`（默认 30s）后 OPEN→HALF_OPEN；HALF_OPEN 探测成功累计 3 次→CLOSED；
  任一失败→OPEN；**探测次数用尽仍未恢复则回退 OPEN**（注释明确写这是修复"卡死在 HALF_OPEN"的 bug）。
- 可注入时间源 + `SlidingWindowCounter`，单测可确定性重放。

### 9.3 降级路径清单（这张表就是工程主线）
| 故障 | 降级为 | 位置 |
|---|---|---|
| MiMo 远程模型熔断 | 路由到本地 RAG 生成 | `ReActAgent` |
| 嵌入/向量库不可用 | 语义缓存降级为"只走精确缓存" | `SemanticAnswerCacheService` |
| 相似度计算异常 | 降级为 n-gram 覆盖率近似 | `RagService` |
| Neo4j 不可用 | 落回内存图谱 | `KnowledgeGraphService` |
| Redis 不可用 | 缓存切面吞异常，业务继续 | `RedisCacheAspect` |
| 检索证据不足 | 拒答/转人工（相似度阈值 0.50） | `RagService` |
| K8s 探针 | health 只反映自身存活，可选依赖不计入 | `agent-ops/application.yml` |

最后一条尤其值得讲：**"健康检查的语义设计"本身就是可用性设计**——
ops 服务的 Neo4j 有内存降级，若把 Neo4j 计入 health，没起数据库时 K8s 会一直判定 Pod 不健康。

## 10. 外部中间件

- **Milvus**（`ai_mall_kb` 知识库 / `ai_mall_chat_memory` 长期记忆，**物理隔离**避免历史对话被知识检索命中）。
  客户端参数是踩坑后的结果：`idleTimeout 180s` + keepalive 20s/5s + `keepAliveWithoutCalls(true)`。
  ⚠️ 维度/metric/index 类型未显式指定，由 EmbeddingModel 与 Milvus 默认决定（面试需如实说）。
  召回策略：向量侧 `topK*4` 过召再截断、BM25 侧 `limit(topK)`，然后 RRF 融合。
- **Neo4j**（`Service` / `FailureMode` 节点，`DEPENDS_ON` / `HAS_FAILURE_MODE{prior}` 关系）：
  变长路径 `MATCH path=(s)-[:DEPENDS_ON*1..5]->(d)`、上游回溯 `*0..3`。
  ⚠️ 未显式 `CREATE INDEX`；启动时 `DETACH DELETE` 重建图（演示取向）。
- **Kafka**：`aiops.{alerts,events,commands,audit}` 四个 topic，四个消费者组，
  `auto-offset-reset: earliest`，`StringSerializer` + JSON 消息体。
  ⚠️ `listener.auto-startup: false` 且本地同步模式为默认——**Kafka 路径在演示里没真跑过**（诚实说明）。
- **Elasticsearch**（mall-search：IK 中文分词 + 同义词规则 + 索引模板）：**已实现但默认不启用**
  （compose 里在 `profiles:[search]`）。
- **MinIO**：bucket 检查/建桶/策略/上传删除都在 `MinioController`，同时也是 Milvus 的依赖；
  ⚠️ 业务侧上传是否被前端实际调用需谨慎表述（另有阿里云 OSS 直传回调一路）。

## 11. 可观测性

- **traceId**：`TraceFilter` 读/生成 `X-Request-Id` → 放 MDC `traceId` → 请求结束移除，有单测。
  ⚠️ **MDC 只在过滤器线程内有效**，异步/线程池要自己传递（Agent 侧因此独立生成 traceId）。
- **访问日志切面** `WebLogAspect`；**审计**落 Redis List，入库前脱敏（`SensitiveDataMasker.snippet()`）+ 截断 500 字，
  写失败只记日志不阻断主流程。
- **Micrometer 打点分层**：客服按**阶段**而不是按接口——
  `agent.customer.stage` Timer，tag `stage`∈{chat, rewrite, retrieve, rerank, generate, llm, tool, evidence, workflow, graph, vision, cache} + `outcome`，
  开 `publishPercentileHistogram` 可直接出 P95；运维用 `Gauge` 把采集到的指标回写给 Prometheus 抓取。
  这个"按阶段打点"的设计比"按接口打点"更适合定位 LLM 链路的耗时分布。
- **Actuator**：网关暴露 `health,info,circuitbreakers,circuitbreakerslimevents`；
  ops 暴露 `health,info,metrics,prometheus` + probes；⚠️ **mall-admin/portal/customer 未配 management 段**
  （走默认 `/actuator/health`），说明可观测性是逐服务补的、不统一。

## 12. 部署与 CI/CD

- **Dockerfile（两阶段）**：`maven:3.9-eclipse-temurin-21-alpine` 先 COPY 各层 pom +
  `mvn dependency:go-offline` 预热依赖层 → 再 COPY 源码 `mvn package -DskipTests`；
  运行阶段 `eclipse-temurin:21-jre-alpine`，**非 root 用户**，`ENTRYPOINT java -jar`。
  ⚠️ **未设 TZ / 未设 JVM 参数**（无 -Xms/-Xmx/GC 选择）——这是可以立刻补上的加分项。
- **K8s**（`infra/k8s/`，6 份清单）：ConfigMap 放配置、**Secret 引用不硬编码**（`JWT_SECRET` 等走 `secretKeyRef`）、
  readiness/liveness 探针、`podAntiAffinity`（portal 2 副本按 hostname 分散）、
  requests/limits 256Mi~512Mi。⚠️ 未声明 `strategy`，用默认 RollingUpdate(25%/25%)。
- **CI**（`.github/workflows/ci.yml`）：push/PR → Java 21 → 缓存 `~/.m2` → `mvn compile` →
  `mvn test` **只跑 6 个核心模块** → 上传 surefire + JaCoCo 报告。
- **CD**（`cd.yml`）：push 打 tag → 7 模块 matrix 打包推镜像（`:latest` + `:<tag>`）→
  **仅 tag 触发**时部署：apply 清单 → sed 替换 `${DOCKER_REGISTRY}` → `kubectl rollout status` 逐个等待。
- **测试成本控制**：父 POM 把 `benchmark,integration,benchmark-manual` 三个分组默认排除，
  日常构建不需要 Docker（需要时 `-Dexcluded.groups=none`）。
  ⚠️ JaCoCo 只出报告，**无 `check` 门禁与覆盖率阈值**——面试如果被问覆盖率，要诚实说这一点。

## 13. 连接池与性能调优（有调优痕迹，可直接讲）

- Druid 分环境配置：`dev` initial-size 5 / min-idle 10 / **max-active 50（注释写明"压测调优 20→50"）**；
  `prod` 收紧到 20；`test` 5 并开 `test-while-idle` + `validation-query: SELECT 1`。
- HTTP 客户端：agent-test 用 Apache HttpClient5 连接池（maxTotal 50 / perRoute 25），
  **连接超时 5s、读超时 10s 分级**；场景对话单独用 120s 读超时（因为 LLM 路径实测可达 ~25s）。
  这就是"**超时按下游成本分级**"的实践。

## 14. 值得讲的技术亮点（后端视角，可直接背）

1. **下单幂等三段式**：唯一键 + 参数指纹（防同 key 换参数）+ `FOR UPDATE` 串行 + 结果回放（防超时重试）。
2. **Lua 原子扣库存**替代 `DECRBY`，并说明"扣成负数需回滚且有窗口"。
3. **自研分布式锁完整生命周期**：可重入 + 看门狗续期 + requestId 校验解锁。
4. **自研三态熔断器双路判定 + 一个真实卡死 bug 的修复叙事**。
5. **限流按下游成本分层**（search 20 → ops 3），而不是一刀切。
6. **缓存故障隔离**（缓存切面吞异常）+ 完整降级链清单。
7. **健康检查语义设计**：可选依赖不计入探针，避免误杀 Pod。
8. **DDL 幂等化**：一份脚本既能建库也能给存量环境补列。
9. **可观测性按阶段打点**（12 个 stage + outcome），可直接出 P95。
10. **Milvus 通道保活**：从压测 46.72% 失败率反推 SDK 默认值问题并修掉。
11. **测试成本控制**：Testcontainers 分组默认排除，日常构建不需要 Docker。
12. **网关路由排错注释沉淀**：把"路由前缀冲突导致会员端 401 / 品牌弹窗 500"的成因写进配置注释。

## 15. 后端视角的自我评价（面试可以这么说）

> "这个项目里我做得比较扎实的是**稳定性基建**：幂等、锁、熔断、限流、降级、健康检查、
> 可观测性分层。这些和 AI 无关，但它们决定了 AI 链路能不能在异常时优雅降级——
> 比如远程模型熔断后回落到本地 RAG，这个降级能生效的前提是熔断器状态机是对的。
> 我也清楚短板：没有统一的异步/线程池治理、没有覆盖率门禁、缓存一致性策略比较朴素、
> 深分页没处理。这些我在文档里都标了 TODO 而不是假装不存在。"
