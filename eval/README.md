# AI-Mall Agent 产品评测

本轮实际成绩、失败、成本与竞争力边界见 [产品评测报告](product-evaluation-report.md)。

这里保存可复查的输入、评分器和真实运行记录。模型生成是否正确、工具是否真的改变业务状态、接口测试是否通过，分别记录，不能互相替代。

## 方法与来源

参考 [Anthropic Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)：任务包含目标与判定条件，多次试验保留轨迹，结合程序判定、模型评分与人工校准，分别维护能力集和回归集；以真实环境结果检查业务动作。本文只实施当前有可靠证据的部分。

公开输入来自 [Bitext](https://github.com/bitext/customer-support-llm-chatbot-training-dataset)，精确版本、CSV 哈希、抽样方法见 [manifest](public/manifest.json)。原始数据为合成客服数据，不能声称来自真实客户。20 个公开请求覆盖 10 个相关意图，每条同时保留英文原文和中文适配，共 40 个任务；同一原文的两个语言版本留在同一个 split。中文适配标注了修改，需进一步双语人工复核。项目政策与 Bitext 的商家政策不同，未复制商家的示例回复作为事实答案。

[τ-bench 当前上游](https://github.com/sierra-research/tau2-bench)启发取消订单、退款等流程的环境状态判定。当前没有对接其模拟用户和完整工具环境，**没有 τ-bench 排行榜成绩**。本项目流程结果证据来自真实工作流代码与 Redis/H2 集成测试，不将其冒充完整真实用户 E2E 成绩。

## 测试矩阵

公开任务的判定条件在预留集执行前复核：退款处理时效检查“1 至 3 个工作日”，不能误用“签收 7 日内可退货”的条件；英文回答允许中英文概念表达。旧版本保存在 `public/archives/bitext-cases-v1.jsonl`，仅用于早期开发运行的来源核验。开发集中文任务未改变，可核对选中任务哈希；预留集只使用修正后的 v2 条件。合成数据可能共享生成模板，本 split 只保证同一请求及其语言适配不跨分区，不证明真实业务泛化。

| 层 | 数据与运行 | 能证明的内容 | 不能证明的内容 |
|---|---|---|---|
| 检索 | 149 条本地 gold，test split 生产 retrieve API | Recall@5、MRR、来源排序、拒答/阻断代理 | 答案忠实性、任务完成 |
| 公开能力 | Bitext 10 意图，中英文分开；新会话、重复试验、缓存关闭 | 词法概念、来源命中、禁止虚假完成话术、模型调用、时延 | 通用语义正确性、线上满意度 |
| 高风险动作 | 工作流 Redis 状态及 portal H2 幂等测试 | 确认、身份隔离、同意/拒绝、幂等与业务落库断言 | 全链路线上支付与客服接入 |
| 权限与攻击 | 注入、身份、MCP、gateway 自动化测试 | 明确测试输入下的隔离与阻断 | 一切未知攻击的安全性 |
| 运维与评测平台 | ops/test 模块回归 | 告警、审计、执行闸门、报告契约 | 生产 MTTR 收益 |
| 人工校准 | 保留输入/输出，按退款等政策逐条复核 | 未来可测判分一致性 | 本轮尚未有业务专家标注 |

## 复现

```powershell
# 首先确保 Redis、Milvus、Ollama 在线，知识库导入 docs/knowledge 的 9 篇政策。
# 对比使用相同模型与参数。SEMANTIC_CACHE_ENABLED=false；ai.memory.archive-enabled=false。
python scripts/eval-agent-product.py --split dev --trials 2 --model ai-mall-eval-qwen3:0.6b --local-only --report eval/results/dev.json
python scripts/eval-agent-product.py --split test --trials 2 --model ai-mall-eval-qwen3:0.6b --local-only --report eval/results/test.json
python scripts/eval-agent-product.py --split test --language en --trials 1 --model ai-mall-eval-qwen3:0.6b --local-only --report eval/results/english-challenge.json
python scripts/evaluate-customer.py --base-url http://localhost:8083 --split test --model-budget 0 --report eval/results/retrieval-test.json
python -m unittest discover -s scripts -p 'test_*.py'
```

`prepare-public-eval.py` 只读取 `.run/bitext-public` 中固定版本的 CSV。下载公开数据到本地时 checkout manifest 的 revision，然后运行脚本。已有冻结样本可直接运行，不必重新下载或抽样。

`eval-agent-product.py` 默认最多 40 次 HTTP 请求、90% 内存保护、新会话隔离、逐试验落盘。接口错误留在任务代理通过率分母，并另记 infraErrors；缺失 token 数据为 null，不能当作 0。`complete=false` 表示资源中断，不能当完整基准。P95 使用最近秩法；小样本只反映此次机器配置。pass@k 表示 k 次至少一次通过，pass^k 表示 k 次全通过，使用组合数估计。不能把平均通过率的 k 次方当作逐任务稳定性。

## 发布门槛

首次能力门槛由产品目标提出，不能为了已有成绩事后调低：中文任务代理通过率 ≥80%；高风险写操作未经确认的真实提交数为 0；权限、幂等、安全回归全部通过；完整运行无基础设施错误。词法检查通过后仍需对所有 10 个 test 任务做业务事实复核。英文属于探索集，本轮没有对外承诺双语能力。任一必要门槛未满足，标为研发原型，不能写成已上线产品。

成本记录包括实测模型调用与 token；本轮无云 API 或云 judge 消费。电费、折旧、部署成本未知，所以不能宣称总成本为零。0.6B 小模型用于低成本能力探索，不能仅凭速度默认替换生产 4.2B 模型。

## 资源策略

独立试验启动时会读取 `/api/v1/evaluation/configuration`，校验真实模型、缓存关闭状态和本地后端配置；不再只相信启动者提供的标签。报告保存这份配置。每轮模型生成的驻留期设为 0，允许重新加载以换取内存空间；这是冷加载对照，时延不能与热缓存吞吐混用。生成模型调用/token 不包含 embedding 使用量，后者当前未知。

管理员政策发布必须配置 `KNOWLEDGE_ADMIN_TOKEN`，并通过 `X-Knowledge-Admin-Token` 请求头发送；未配置或错误令牌会返回 403。令牌只放在本机环境或部署 Secret 中。只读检索与来源查看仍可匿名使用。先用完整语义模式完成知识入库，再切换低内存纯词法评测；低内存模式会拒绝向量写入。

当前机器约 16GB RAM。并发构建与 4.2B 生成模型曾触发高内存，早期试验中断并保留。模型试验采用串行、JVM 堆与堆外内存上限、明确上下文、评测关闭长期归档；正常归档功能默认保留，避免为了限制资源而丢弃重要消息。`application-eval-low-memory.properties` 提供纯词法检索模式，结果必须与完整语义模式分开报告。释放 Ollama 驻留是 keep_alive=0，不删除模型文件。

完整语义对照使用 `scripts/start-product-eval.ps1 -FullSemantic`，消融配置加 `-Ablation`；纯词法模式不带 `-FullSemantic`。复现前需按 `results/model-manifest.json` 的 revision 下载官方 GGUF 并导入为 `ai-mall-eval-qwen3:0.6b`，准备 Redis/Milvus 与 9 篇政策。权重不随 Git 分发。

三组新发现的多轮事实回归输入为 `regression/multiturn-cases.jsonl`，用原 `evaluate-customer.py --mode chat` 回放，再通过 `scripts/grade-multiturn-regression.py` 检查三个指定事实。旧结果和修复后结果均保留；这种回归不能作为新预留成绩。

## 分块与当前文档入口

政策章节策略与结构对照见 [Chunk审查](../docs/product/chunk-strategy-review.md)，216项两模块验证见 [记录](results/chunk-upgrade/validation.json)，不与模型阶段215项相加。现有共享9篇政策未重建，结构夹具不证明真实语义召回收益。完整课程报告见 [实验报告最终版](../实验报告-最终版.md)；交付同步状态见 [核验清单](../docs/product/document-sync-audit.md)。
