# AI Mall 简历证据包使用说明

这个文件夹是项目结论的佐证材料，不是商城运行必需的数据，也不是一份完整简历。它用来回答面试官的追问：测试什么、用什么模型、分母是多少、怎么判定通过、失败在哪里、改动后有没有退化。

建议保留，面试时按问题挑几份展示；投递正文只写关键决策与有边界的结果，不把全部JSON塞进简历。可以备份归档，不需要跟商城服务一起运行。

| 看什么 | 文件 | 用途 |
|---|---|---|
| 总体结果 | `summary.json` | 快速定位实验结论，仍要结合外层评测报告阅读 |
| 对照是否公平 | `comparison-provenance.json`、`semantic-ablation-dev.json`、`semantic-candidate-dev.json` | 核对输入、配置和开发对照，不能把开发集成绩当盲测或线上解决率 |
| 首次预留与语言限制 | `semantic-test.json`、`english-test.json` | 保留首次中文测试和英文探索结果，防止只挑高分 |
| 来源与事实是否一致 | `retrieval-test.json`、`multiturn-before-fact-checks.json`、`multiturn-after-fact-checks.json` | 区分来源命中与回答事实正确，展示已知错误回归 |
| 环境与失败诊断 | `diagnostic-*.json` | 保留缓存混淆、启动失败、内存中断等失败，不是可直接写成成果的数字 |
| 模型来源与软件边界 | `model-manifest.json`、`unit-tests.json`、`redis-integration-tests.json` | 说明模型来源、测试范围；测试条数不等于客服准确率 |
| 后续改进 | `rag-upgrade/`、`recruiter-upgrade/`、`ui-smoke/` | 组件回归、招聘审查和实际访客界面验证 |
| 模型选择 | `model-selection/` | 本地目录读取、请求模型隔离、向量模型拒绝与内存保护；云端格式为模拟验证 |
| 会员登录 | `member-login-verification.json` | 原有演示会员登录、信息与订单列表读取；不含密码、JWT或完整交易结果 |
| Chunk分块 | `chunk-upgrade/` | 长规则/例外结构实验、分块策略和版本保护；不等同真实embedding召回提升 |

这套资料有用的前提是能解释口径。40项公开任务来自20条请求的英文和中文适配，193项软件测试是某一范围的运行，7/7界面冒烟是已见场景回归；这些数字都不能自动代表真实用户解决率、通用语义准确率或商业降本。

文件可能来自不同版本和运行环境，要看各自的模型、知识版本、日期及说明。旧结果保留为历史证据；不能把多个版本的测试数叠加成最新全量成绩。当前产品简历与STAR面试稿在此目录的上一级。
