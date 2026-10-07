# AI-Mall 四岗位项目讲述与证据

更新日期：2026-10-07。项目是一套可持续迭代的电商与客服研发原型，可以从产品经理、Java 后端、AI 应用开发、测试开发四个方向讲清问题、取舍、实现和验证。投递时选择与岗位最相关的一条主线，避免把技术名词全部堆进简历。岗位匹配还取决于具体 JD 和本人能否解释代码，本材料不承诺适用于所有岗位。

## 共用项目主线

客服既需要回答政策，也需要读取实时订单，还可能触发售后写操作。政策事实来自可追溯知识，个人订单依赖鉴权接口，办理先出草稿并由用户确认。开发中用失败样例校正检索、交互和权限，再用确定性回归检查改动。新增的测试报告对比帮助区分“新坏了什么”“修好了什么”和“只是环境不可用”。

电商基础代码与部分前端结构复用 mall 生态；[第三方说明](../../THIRD_PARTY_NOTICES.md)说明项目演进范围，不能替代个人贡献证明。本人应按实际参与情况选择下面的措辞，并能解释至少一个代码分支、一个失败样例和一个验收判断。AI 辅助实现与人工核验要如实说明。

## 四个方向各讲什么

| 方向 | 面试主线 | 三个可展开的取舍 | 现场展示 | 当前边界 |
|---|---|---|---|---|
| 产品经理 / AI 产品经理 | 把客服任务与风险拆成 MVP 和验收规则 | 政策用知识、实时状态用 API；写操作为何确认；离线结果为何不直接算用户解决率 | [产品方案](../product/ai-pm-product-brief.md)、[报告对比验收](report-comparison-acceptance.md)、会员端正常/拒答/未登录路径 | 没有真实访谈、商家试点、CSAT 和商业降本数据 |
| Java 后端开发 | 多模块业务与 Agent 边界下的数据一致性 | owner-scoped 幂等；参数变化冲突而非错误回放；Redis 确认状态与数据库写入之间的恢复 | 下单幂等代码与 H2 双线程测试、报告 ID 留存及失效处理 | H2 测试不等于生产 MySQL、多实例或完整支付验收 |
| AI 应用开发 | 让模型围绕证据、工具与状态工作 | 按问题选检索路径；政策条件与例外一起分块；证据不足/未登录/未确认时阻止错误回答或操作 | 分块结构实验、PolicyAnswerComposer、工具权限、确认工作流 | 没有训练基础模型；真实生成质量和云服务收益仍需重新验证 |
| 测试开发 | 让自动化回归的红灯可信、差异可追踪 | OpenAPI 决定期望值；环境噪音独立归因；UUID 不作为跨轮次用例身份 | 契约守卫、比较 API、后台两轮报告差异、受保护入口与离线验收 | `$ref` 部分结构仍跳过；未覆盖全部 UI、并发压测和业务 DB 副作用 |

## 产品经理：从用户任务讲到验收决策

30 秒介绍：“这个项目解决客服政策答错、实时订单被编造、办理结果不透明的问题。我把首版拆成政策咨询、登录查单和用户确认后的售后办理，为正常、未登录、证据不足、拒绝确认与失败恢复定义不同路径。评测后把条件遗漏和错误来源转成回归任务。它还是研发原型，我能展示需求、交互和验证证据，但没有真实商家上线效果。”

追问时展示产品方案中的任务、优先级与指标定义，再用[报告对比需求](report-comparison-acceptance.md)讲一项这轮实际交付：为什么仅看通过率无法区分修复、回归和用例范围改变；为什么没有基线时要提示不可用。可以提出真实用户任务观察的后续计划，不能说已经完成。

## 后端开发：从重复请求讲到业务一致性

30 秒介绍：“我重点关注电商与 Agent 调用中的重复请求、权限和状态一致性。下单按用户和幂等键管理操作，用请求参数指纹识别同键不同请求，并回放已完成结果；售后通过草稿版本和确认状态避免重复写入。测试用 H2 双线程验证重复下单，用隔离测试验证报告落盘、基线失效与 API 鉴权。生产 MySQL 并发和完整交易链路仍需专项验收。”

| 可追问问题 | 代码与测试 |
|---|---|
| 为什么不能只把幂等键放 Redis；同键参数变化怎么办 | [OmsPortalOrderController](../../mall-portal/src/main/java/com/ai/mall/common/portal/controller/OmsPortalOrderController.java)、[H2 并发测试](../../mall-portal/src/test/java/com/ai/mall/common/portal/controller/OmsPortalOrderControllerIdempotencyH2Test.java) |
| 用户确认后进程重启或下游超时怎么办 | [AfterSaleWorkflowService](../../agent-customer/src/main/java/com/ai/mall/agent/customer/service/workflow/AfterSaleWorkflowService.java)、[Redis 集成测试](../../agent-customer/src/test/java/com/ai/mall/agent/customer/service/workflow/AfterSaleWorkflowRedisIntegrationTest.java)；集成测试使用真实 Redis，下游模型与业务工具模拟，本輪离线验收排除 integration |
| 基线淘汰、重复用例、鉴权失败能否返回成功 | [比较服务](../../agent-test/src/main/java/com/ai/mall/agent/test/service/report/TestReportComparisonService.java)、[Controller 验收](../../agent-test/src/test/java/com/ai/mall/agent/test/controller/TestReportComparisonControllerTest.java)、[报告重启测试](../../agent-test/src/test/java/com/ai/mall/agent/test/service/report/TestReportStoreTest.java) |

## AI 应用开发：从 RAG 讲到可控 Agent

30 秒介绍：“我把模型放在有证据、有权限、有状态的业务流程中。客服按问题路由召回，政策分块保留条件与例外，回答先检查证据，个人数据读取走业务工具，写操作先确认。验证分别看检索、来源、工具和状态，保留错误样例。当前能证明代码和离线行为，不能把结构回归说成生成语义准确率，也没有做基础模型训练。”

| 可追问问题 | 代码与测试 |
|---|---|
| 为什么高召回仍可能答错 | [RAG 取舍](../product/rag-gap-and-upgrade.md)、[ReActAgent](../../agent-customer/src/main/java/com/ai/mall/agent/customer/service/agent/ReActAgent.java)、[回答证据回归](../../agent-customer/src/test/java/com/ai/mall/agent/customer/service/agent/ChatEvidenceRegressionTest.java) |
| 为什么分块不严格固定 512 字符 | [分块审查](../product/chunk-strategy-review.md)、[PolicySectionChunker](../../agent-customer/src/main/java/com/ai/mall/agent/customer/service/rag/PolicySectionChunker.java)、[结构实验](../../agent-customer/src/test/java/com/ai/mall/agent/customer/rag/PolicyChunkBoundaryExperimentTest.java) |
| 如何避免模型补写政策条件 | [PolicyAnswerComposer](../../agent-customer/src/main/java/com/ai/mall/agent/customer/service/agent/PolicyAnswerComposer.java)、[对应测试](../../agent-customer/src/test/java/com/ai/mall/agent/customer/service/agent/PolicyAnswerComposerTest.java) |

模型入口支持本地与兼容云端，但历史云验证为模拟 HTTP、本地生成受内存限制。面试涉及模型效果时展示[模型说明](../product/model-selection.md)与[评测报告](../../eval/product-evaluation-report.md)原始条件。149 条自建检索用例、20 条公开合成请求及双语适配的 40 项任务不能混为独立用户。

## 测试开发：从 oracle 讲到回归闭环

30 秒介绍：“测试 Agent 从 OpenAPI 生成契约用例，成功码必须有声明，未声明的错误码只作 4xx 弱断言，避免让模型编造期望值。执行前记录环境状态，并将连接噪音与业务失败分开。新增报告对比按模块、方法、路径、名称匹配，自动标出新增失败、修复和范围变化；基线缺失、身份冲突与环境未验证都有明确输出。它仍有 `$ref` 和真实业务副作用覆盖不足的限制。”

| 可追问问题 | 代码与测试 |
|---|---|
| AI 生成了契约中不存在的端点怎么办 | [TestCaseContractGuard](../../agent-test/src/main/java/com/ai/mall/agent/test/service/generator/TestCaseContractGuard.java)、[守卫测试](../../agent-test/src/test/java/com/ai/mall/agent/test/service/generator/TestCaseContractGuardTest.java) |
| 一片红灯怎样区分环境与产品缺陷 | [FailureClassifier](../../agent-test/src/main/java/com/ai/mall/agent/test/service/insight/FailureClassifier.java)、[分类测试](../../agent-test/src/test/java/com/ai/mall/agent/test/service/insight/FailureClassifierTest.java)、[差异验收](report-comparison-acceptance.md) |
| 测试能力如何给其他 Agent 调用 | [McpProtocolService](../../agent-test/src/main/java/com/ai/mall/agent/test/service/mcp/McpProtocolService.java)、[MCP 测试](../../agent-test/src/test/java/com/ai/mall/agent/test/service/mcp/McpProtocolServiceTest.java) |

## 五分钟演示与复现

1. 第一分钟说目标岗位的项目问题和一项取舍，用上面对应的 30 秒介绍开场。
2. 第二分钟展示一个输入与拒绝/失败分支：未登录不读订单、未确认不写售后，或基线失效不能比较。
3. 第三分钟打开对应源码与测试，解释测试输入、预期和模拟边界。
4. 第四分钟展示[本轮验收记录](../../eval/results/career-readiness/validation.json)与[合成比较输出](../../eval/results/career-readiness/report-comparison-example.json)。合成输出验证比较规则，不代表真实发现五个商业缺陷。
5. 第五分钟说明已知限制及下一项最有价值的验证。

```powershell
# 在仓库根目录运行；需要 Java 21、Maven、Python、Node/npm 和已安装的后台前端依赖。
python scripts/verify-career-readiness.py
# 同步到求职材料目录；校验链接、文稿与证据文件。
python scripts/sync-project-documents.py --delivery-dir 'E:\选修课\雪\简历'
python scripts/sync-project-documents.py --delivery-dir 'E:\选修课\雪\简历' --check
# 只同步/核验本轮四岗位新交付，不覆盖原有 Word/PDF。
python scripts/sync-project-documents.py --delivery-dir 'E:\选修课\雪\简历' --career-only
python scripts/sync-project-documents.py --delivery-dir 'E:\选修课\雪\简历' --career-only --check
```

离线脚本明确排除真实语义 embedding、容器集成和压测；不启动 Docker、不调用真实模型、不写本机业务数据库。JSON 按当前运行的 Surefire 文件统计通过/失败/跳过，不能与历史 215、216 或其他阶段测试数累加。

## 继续优化的优先级

| 优先级 | 工作 | 完成标准 | 主要岗位收益 |
|---|---|---|---|
| 已交付本轮 | 测试报告对比、受保护 API、后台入口和可复现离线记录 | 分类、身份歧义、环境异常、失效基线、持久化身份与前端构建通过 | 四岗位共用的一条需求→实现→验收故事 |
| P0 待验收 | 原有会员登录后的完整交易与售后链路 | 在专用演示数据上验证真实落库、拒绝确认无副作用、重复提交一次生效；保留失败与恢复记录 | 后端、测试、产品 |
| P1 待开发 | OpenAPI 本地 `$ref` 解析与结构覆盖 | 支持 components/schemas，循环和外部引用有边界；用真实契约反例验证 | 测试、后端 |
| P1 待验证 | 新冻结客服任务集与人工校准 | 明确未用于调参样本、政策事实复核、分母与未评分，按目标模型重测 | AI、产品、测试 |
| P1 待开展 | 真实买家与客服任务观察 | 记录招募对象、任务、失败、同意与观察结论，避免把计划写成访谈经历 | 产品 |
| P2 条件成熟后 | 多实例幂等、MySQL 并发与成功成本 | 固定环境、输入与负载；同时记录质量、耗时、资源与副作用 | 后端、AI、测试 |

前端岗位可沿会员端宽屏/移动交互展开；SRE 岗可沿运维 Agent 的检测、根因与变更门控展开；安全方向可沿工具鉴权和拒绝策略展开。这些是扩展讲述入口，需要补对应岗位的专项证据，不能把本轮四岗位验收直接说成算法研究、模型训练或生产 SRE 经历。
