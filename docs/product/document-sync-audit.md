# AI Mall 文档更新核验清单

核验日期：2026-10-07。核验仓库正文、简历目录交付副本、证据附件、简历Word/PDF及当前版本说明。这里确认文档与已有实验记录一致，没有重新运行推理、语义评测或交易流程，也不改变冻结测试结果。

## 本次发现及修正

最初分块分析只写入产品评测与策略审查，遗漏根目录课程报告。随后发现最终报告的其他章节还保留旧知识篇数、测试总数、运行说明及绝对“零幻觉”表述；演讲稿与部分面试材料也没有跟进模型和分块阶段。此次直接修正受影响正文，并给保留的历史工程材料添加当前入口，避免让旧数据被读成最新结果。

最终实验报告现为V1.3：摘要、技术表、知识规模、第六章分块、§15.3任务评测、§20.3阶段验证、§23.2安全启动、最终结论、附件、汇总/版本及附录D均已核对。数据库密码不写入报告；采用安全提示输入。个人分工和学校信息沿用原资料，本次不补写个人经历。

## 当前材料与交付对应

| 仓库入口 | 简历目录文件 | 核验重点 |
|---|---|---|
| [实验报告最终版](../../实验报告-最终版.md) | 实验报告-最终版.md | 全文各章节、历史与当前口径；不只补第六章 |
| [课程演讲稿](../../汇报演讲稿-智能客服RAG系统.md) | 汇报演讲稿-智能客服RAG系统.md | 分块选择、当前配置和演示范围；不承诺六服务在线或完整交易 |
| [产品评测报告](../../eval/product-evaluation-report.md) | AI-Mall评测报告.md | 冻结历史成绩、当前阶段、失败及资源边界 |
| [产品方案](ai-pm-product-brief.md)、[竞争力](competitive-and-commercial-plan.md) | AI-Mall产品方案与竞争力.md | 政策完整性验收、优先级、模型入口与商业未测项 |
| [STAR与AI指挥](ai-pm-star-project.md) | AI-Mall_STAR面试稿与AI指挥方法.md | 215模型阶段与216分块阶段分开，完整AI任务与失败故事 |
| [面试证据导读](interview-evidence-guide.md) | AI-Mall面试讲解与待补齐项.md | 新分块追问、登录只读与完整交易边界 |
| [招聘审查](recruiter-review.md) | AI产品经理招聘审查与技术应用.md | 旧187阶段与后续能力分开，技术候选不冒充已上线 |
| [RAG差距](rag-gap-and-upgrade.md) | RAG优化与产品取舍.md | 标题/条款保护已实现，语义/业务收益未验证 |
| [简历参考](resume-reference-review.md) | AI产品经理简历参考与改写依据.md | 外部检索日期保留，最新项目表达跟随证据 |
| [投递项目段落](resume-project-section.md) | AI产品经理项目简历_STAR文字版.md、.docx、.pdf | 补政策分块完整条款/例外的技术理由，保持Agent评测主线与一页版式 |
| [模型说明](model-selection.md) | 智能客服模型选择说明.md | Ollama优先、云API入口、模拟验证、本地内存暂停 |
| [Chunk审查](chunk-strategy-review.md) | Chunk分块策略与验证.md | 原选型不足、结构结果、现代方法取舍与未重建状态 |
| 本清单 | 文档更新核验清单.md | 文件范围、证据及复核方式 |

根README、docs索引、eval方法与证据包说明同步入口和分母；历史后端/Agent面试材料、RAG实证和缓存报告保留原结果但明确阶段与新入口。模板、知识政策原文、API参考、历史冻结实验/存档、第三方声明与基础设施规范不因本次文稿刷新而改写，不以重写历史成绩实现“全部更新”。

## 必须保持一致的事实

| 事实 | 对应证据 | 口径 |
|---|---|---|
| 2363字符、8组规则/例外 | [结构实验](../../eval/results/chunk-upgrade/boundary-experiment.json) | 固定512为5/8、按句512为6/8、政策章节与整篇均8/8；开发结构覆盖，不是语义正确率 |
| 政策512产生8块，平均303.0、最大609字符 | 同上 | 完整单元允许超软目标，不证明512最优或token收益 |
| 分块阶段216项 | [分块验证](../../eval/results/chunk-upgrade/validation.json) | common62+customer154，不含网关重跑；现有共享政策未重建，无新增模型调用 |
| 模型入口阶段215项 | [模型验证](../../eval/results/model-selection/validation.json) | common62+customer143+gateway10；云模拟，本地试答被内存守门暂停 |
| 会员登录、信息与订单列表只读成功 | [登录验证](../../eval/results/member-login-verification.json) | 原账号未重置，非完整下单/支付/售后/人工闭环 |
| 首次中文18/20、英文3/10 | [总评测](../../eval/product-evaluation-report.md) | 历史代理检查保留失败；后续已知回归不重命名为新盲测 |

## 可复查的同步与检查

本次Word简历已重新生成，PDF从同一份Word导出并保持一页。打包的render_docx在本机缺少LibreOffice而未成功；改用本机Word兼容COM导出和PDFium渲染，已打开 [简历渲染页](../../eval/results/document-sync/resume-page.png) 检查文字、换行与边界，未见裁切、重叠或缺字。不把备用渲染说成打包工具成功。

[同步脚本](../../scripts/sync-project-documents.py)只同步明确的交付文件与Git跟踪的评测证据，不删除文件、不改数据库、不运行模型。对Markdown按交付位置转换链接；原始JSON、TXT、图片及公开冻结任务按字节校验。文档副本比较转换链接后的全文，Word与规范项目段落比较正文，PDF检查新分块决策及一页数量，另由渲染页人工核对布局。自动文字检查不能替代视觉检查或语义质量评测。

```powershell
# 使用工作区依赖提供的Python路径；先完成简历Word/PDF生成，再同步
python scripts/sync-project-documents.py --delivery-dir '<简历目录>'
# 只读检查，不更改交付文件
python scripts/sync-project-documents.py --delivery-dir '<简历目录>' --check
```

检查记录见 [validation.json](../../eval/results/document-sync/validation.json)，包含文件哈希、镜像一致性、链接、报告必要章节及简历文本验证。生成记录的日期是文档核验日期，不是重新测得所有实验成绩的日期。
