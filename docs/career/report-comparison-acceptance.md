# 测试报告基线对比：需求、设计与验收

更新：2026-10-07。目标使用者是开发、测试和质量负责人。只看通过率无法知道哪些用例退化、哪些修复、哪些只是测试范围变化；需要能指向具体用例的两轮差异。

## 需求与验收规则

| 输入 / 场景 | 输出与验收标准 |
|---|---|
| 同一模块，同一身份 PASS→FAIL | 新增失败 NEW_FAILURE |
| 同一身份 FAIL→PASS | 已修复 FIXED |
| 同一身份 FAIL→FAIL | 持续失败 PERSISTING_FAILURE |
| 仅当前报告出现，不论通过或失败 | 新增用例 NEW_CASE；不算已有用例回归 |
| 仅基线报告出现 | 移除用例 REMOVED_CASE；不算修复 |
| 两边均通过 | 不输出差异项 |
| 用例随机 ID 改变但稳定身份相同 | 正常匹配，不产生伪新增/伪移除 |
| 基线被淘汰、ID 空、模块不同或用例身份冲突 | 明确错误，不能返回一份空的成功比较 |
| 环境缺失、探针跳过或不可达 | comparable=false，说明原因，不给业务回归结论 |
| 探针可达，但单条连接失败 | 单列 ENVIRONMENT_UNAVAILABLE，不能算新增业务失败或修复 |
| 无 HTTP 状态，失败原因无法确定 | 单列 UNVERIFIED 待核对，不计业务回归 |
| 空测试套件或缺失结果详情 | 空套件不可作为回归基线；缺失详情明确报错 |

## 实现取舍

稳定身份为模块、HTTP 方法、API 路径和用例名称；方法大小写归一化，随机 UUID 只标识单次执行。执行器同时保留 method/apiPath，报告落盘视图也保留这两个字段。旧报告缺失字段时，只允许两边名称均唯一的模块+名称回退，并给 warning；不能安全匹配则拒绝。

这个设计面向规则生成及名称稳定的用例。AI 生成名称改变会作为增删而非强行匹配；请求参数、模型配置或阈值改变尚无独立配置指纹，因此状态变化是候选回归信号，仍需复核输入与断言是否相同。通过率不下降、环境可达或没有差异都不意味着系统可以上线。

API：`GET /api/v1/test/reports/compare?baselineId=<id>&currentId=<id>`；经过网关时前缀为 `/agent/test`。沿用 `/api/v1/test/**` 的用户 JWT / 内部令牌拦截器，没有新增匿名入口。404 表示报告不存在或已淘汰；400 表示空 ID；422 表示身份、模块等无法安全比较。正常返回 baselineReportId、currentReportId、comparable、warnings、counts 和 entries。

后台“AI 自动化测试中心”增加基线/当前报告选择、分类计数、用例差异与环境提示。选择不同模块或同一报告时禁止比较；切换选择或请求失败时清空旧结论，避免把旧结果展示为新结果。没有报告时仍显示原有空状态。

不启动业务服务时，可以运行 `npm --prefix frontend run dev`，打开该开发服务器的 `/report-comparison-preview.html`。这个入口复用实际 Vue 页面，以内存适配器返回[隔离验收样例](../../frontend/src/preview/report-comparison-example.json)，顶部明确标注合成预览；不注入登录状态、不连接真实 API、不触发模型或业务写入。正式后台仍通过原有登录与服务鉴权。

## 如何复现与看证据

```powershell
# 只复现报告比较、API鉴权与报告持久化测试；PowerShell 中整体引用 -D 参数。
mvn -pl agent-test test '-Dtest=TestReportComparisonServiceTest,TestReportComparisonControllerTest,TestReportStoreTest,TestExecutorAssertionTest' '-Djacoco.skip=true'
# 复现本轮 Java 业务/安全/Agent、Python评测脚本及后台构建范围。
python scripts/verify-career-readiness.py
```

[比较服务测试](../../agent-test/src/test/java/com/ai/mall/agent/test/service/report/TestReportComparisonServiceTest.java)验证分类、稳定身份、旧报告兼容和歧义；[Controller 测试](../../agent-test/src/test/java/com/ai/mall/agent/test/controller/TestReportComparisonControllerTest.java)使用真实比较服务与真实鉴权拦截器、模拟报告仓库，检验 401/400/404/422 及正常 JSON；[持久化测试](../../agent-test/src/test/java/com/ai/mall/agent/test/service/report/TestReportStoreTest.java)检验身份字段重启后仍保留。

Controller 验收生成 `agent-test/target/report-comparison-example.json`，交付副本见[合成比较输出](../../eval/results/career-readiness/report-comparison-example.json)，覆盖五种业务分类各一例。输入是隔离测试样例，不能说本轮发现了五个真实生产缺陷。运行统计、排除项和日志哈希见[validation.json](../../eval/results/career-readiness/validation.json)。历史生成质量、真实交易与 Redis 集成验证不包含在这个离线范围内。

本轮离线 Java 范围为 385 项通过，Python 评测脚本回归为 15 项通过，后台类型检查与生产构建通过。浏览器以合成适配器核对五种差异、切换清空旧结论、环境异常提示、相同报告禁用及 390px 页面无整体横向溢出；记录见[UI 验收](../../eval/results/career-readiness/ui-validation.json)与[桌面截图](../../eval/results/career-readiness/comparison-preview.jpg)、[环境截图](../../eval/results/career-readiness/environment-preview.jpg)、[窄屏截图](../../eval/results/career-readiness/mobile-preview.jpg)。没有将合成 UI 检查称为真实业务服务端到端测试。

## 下一步

先补比较配置指纹和原始输入/断言摘要，再考虑同一用例多轮状态抖动的 flaky 检测。当前两份报告差异不能识别 flaky，也没有自动选择“生产基线”或发布阻断；需要由使用者明确指定基线并核对测试条件。
