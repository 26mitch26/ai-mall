# 智能客服升级验收记录

日期：2026-10-03。验证使用本机 Java 21、Spring Boot 3.5、Spring AI 1.0、本地 Ollama/bge-m3、Milvus 2.4、Redis 7、MySQL 8。MySQL 验收端口为 13308、Redis 为 16389，Milvus 使用专用集合；没有向正式订单数据库写入测试记录，没有调用付费推理 API。

## 已完成的验证

- 编译和单元测试覆盖 mall-common、mall-security、mall-admin、mall-portal、ai-gateway、agent-customer。客服整套测试包含真实本地 bge-m3 的可选语义评测。
- 最新有效测试报告合计 215 个用例：mall-common 62、mall-security 20、商城 27、网关 8、客服 93 个常规用例及 5 个显式运行的 Redis 集成用例。失败和错误均为 0。mall-admin 完成干净编译打包，当前常规测试发现数为 0，不将其计作已执行测试。
- H2/MySQL 模式实际执行 SQL，验证操作结果回放、参数冲突、用户隔离、两线程同键提交和事务回滚；它不能替代 MySQL 的所有并发语义。
- 独立 Redis 实际执行 Lua，验证草稿在新服务实例中恢复、同一任务并发确认仅有一位执行者、拒绝和版本冲突不执行写入、丢失响应后查账，以及政策即时更新和未来生效的时间顺序。
- 在真实 MySQL 后端新建一个隔离订单，准备售后草稿，再由独立客服 JVM 恢复；两个并发确认分别得到 COMPLETED 和 CONFLICT，重复确认返回既存结果，操作查询返回同一售后 ID。
- 官方 MCP Python SDK 完成版本协商、tools/list 和公开商品查询；网关与直连均验证。协议限制仍是 2025-06-18 无状态 JSON-only profile。
- 本机 Neo4j 在优惠券、退款、运费复合问题中返回 8 条当前版本政策关联路径；最终回答仍引用检索到的正文证据。这验证局部关联服务可用，不证明一般多跳推理准确率。
- 真实 Milvus 与 Qwen3-Reranker-0.6B 返回版本化政策证据；同一知识问句缓存复答的模型调用为 0。未来政策在生效前保留旧内容，生效后切换；旧引用指定版本时仍读取旧原文。
- 本地 Qwen3-VL 完成照片接口验证，返回 requiresReview=true。使用的是合成红色方形图片，仅验证传输、输出结构和人工复核约束；单次冷调用约 27.4 秒，不能据此声称损坏识别准确率。
- 管理前端类型检查与构建、商城前端 H5 构建通过。浏览器验证后台登录、公开客服问答、缓存返回、引用卡片和折叠工具区。1280×720 下聊天区从 28 像素修复到约 168 像素；技术详情与售后工具按需展开。

## 检索对照实验

**当前版本范围：**以下 149 条检索对照和历史注入测量来自前一轮记录。会员端与本机 MySQL 的补充验收新增了 revision registry 过滤和对应 13 项回归；为避免把旧版候选集合上的分数说成当前检出结果，本轮没有重跑全量 149 条基准。后续若要报告“修复后的当前版本 MRR/Recall/P95”，应先在相同隔离环境重跑，并把新旧记录分开保留。

评测集为开发者构造的 149 个用例：114 个政策问题（含 25 个历史追问）、10 个边界问题、10 个无答案问题、5 个冲突问题、10 个注入样例。需要业务方或教师复核，不能称为生产人工标注集。两个划分均覆盖同一 9 篇公开政策，按问句意图组分组，未把同组改写拆入不同划分。

下表是相同知识、同样 topK=5、单并发、生成模型预算为 0 的两轮真实 HTTP 检索测试。规则链路由停止神经服务后触发正常回退获得；不是另一套内存评测器。

| 指标 | 本地神经重排 | 规则回退 |
|---|---:|---:|
| 样本数 | 149 | 149 |
| Recall@5（指定来源覆盖） | 1.0000 | 1.0000 |
| MRR | 0.9289 | 0.9283 |
| 指定来源 precision@5 | 0.2505 | 0.2590 |
| P50 HTTP 耗时 | 1085 ms | 819 ms |
| P95 HTTP 耗时 | 1385 ms | 1040 ms |
| HTTP 错误 | 0 | 0 |
| 生成模型调用 | 0 | 0 |

神经链路的 140 个正常响应实际标记为 qwen3-reranker-0.6b，其余 9 个为输入阻断；回退链路的正常响应实际标记为 feature。两轮顺序运行，缓存与模型驻留可能不同，未计算置信区间，不能宣称显著提升。

补齐中文注入回归后，再跑同一 149 条规则回退链路：HTTP 错误 0，阻断分类 149/149，指定来源 Recall@5=1.0000、MRR=0.9283，P50/P95=822/1132 ms。10 条构造攻击样例全部拦截，不代表对开放攻击分布的安全保证；此最终报告保存在 `.run/final-rag-evaluation.json`。

来源 precision 是与用例指定来源名单的交集，知识库存在重叠条款和有效替代来源，名单并非完整相关性标注。检索拒答信号准确率为 0.9318（132 个可评分用例），并非端到端回答拒答正确率。首轮注入样例拦截 9/10，遗漏“忘记之前的所有指令”已补模式和合法找回密码的负例回归；原始对照表保留修复前的结果，不追改数据。

**选型决定：真实神经模型保留可选接入，默认关闭。** 当前小型中文政策库中 MRR 收益很小，耗时更高；需要新增长文、难负例、复杂查询后再验证启用范围。词法证据校验没有测量通用语义 faithfulness，也没有承诺零幻觉。

## 复现入口

### 会员端与本机 MySQL 补充验收

同日后续验证将商城管理/会员服务切至 Windows MySQL 9.7（本机 3306），客服与网关切至现有 Redis 6379；原有商城数据未重置。成功验证演示会员登录、读取其 4 个个人订单、客服从商城读取在售手机的价格与库存。售后准备后刷新页面仍能恢复草稿，拒绝后为 REJECTED；本轮没有确认商城售后写入。

会员端 `npm run tsc` 与 `npm run build:h5` 通过；修复旧 vue-tsc 与 TypeScript 的兼容问题，同时保留现有 UniApp/TypeScript 版本。1280×720 与 390×844 浏览器验证首页/客服无横向溢出，桌面输入区在首屏可见，手机保留首页 tabBar、隐藏桌面导航。分类和帮助中心导航、原文读取也完成检查。截图在 `docs/assets/member-*.png`。

旧知识索引经正常 ingest 发布为版本化文档，没有清空 Redis。补修同 source 的 legacy 空版本与新版重复展示/召回的问题：有 revision registry 的 source 按版本过滤旧记录，真正未迁移 legacy 仍可检索、引用；补充及原有 `AdaptiveRagBehaviorTest` 合计 13/13 通过，非 clean Maven 打包成功。真实服务重启后清单为 9 个不同来源、均带版本；“签收后多久可以申请退货？”实际返回 5 条当前版本证据，weakEvidence=false，modelCalls=0。记录在 `.run/member-local-retrieval-proof.json`。这是一条检索链路验证，不代表新的总体准确率测量。

会员登录不再保存或回填明文密码；售后订单快照只显示订单号、状态、商品与实付金额。启动脚本按当前源内容哈希同步知识，移除了首次启动删除 `bm25:*` 的逻辑。Docker 清理已归档移除 5 个闲置容器及对应卷，历史验收使用的 13308/16389 验证服务已退役；如复现下面的隔离集成测试，应另行准备独立 Redis，勿将清理测试指向共享 6379。详情见 [Docker 清理记录](docker-footprint.md)。

```powershell
mvn -pl mall-admin,mall-portal,agent-customer,ai-gateway -am clean install '-Djacoco.skip=true' '-Drag.embed.model=bge-m3'
# 集成分组默认关闭；请使用独立、空的验证 Redis。
mvn -pl agent-customer test '-Dtest=AfterSaleWorkflowRedisIntegrationTest,RagPublicationRedisIntegrationTest' '-Dexcluded.groups=none' '-Dagent.verify.redis-port=16389' '-Djacoco.skip=true'
python -m unittest discover -s scripts -p test_evaluate_customer.py
python scripts/mcp-sdk-smoke.py
python scripts/evaluate-customer.py --mode retrieval --split all --model-budget 0
# chat 模式包含历史预热，最多 12 次 HTTP 调用，不含独立模型裁判。
python scripts/evaluate-customer.py --mode chat --max-cases 12
```

`jacoco.skip` 用于避开本机中文路径的旧 JaCoCo fork 问题；本次没有报告覆盖率。默认排除的其他容器/压测分组未全部执行。构建遇到残留旧包名 class 与本机内存压力后，以干净构建和受限构建堆内存复验；不把基础设施故障算作性能提升。

原始证据在本机 `.run/production-rag-evaluation.json`、`.run/feature-fallback-evaluation.json`、`.run/live-agent-report.json` 和各构建/集成测试日志中；这些目录不进 Git。dev 启动只以 IF NOT EXISTS 初始化新幂等表，生产采用独立迁移。部署步骤、确认流程和面试追问见 [运行与答辩手册](agent-upgrade-guide.md)。
