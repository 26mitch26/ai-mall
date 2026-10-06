"""Build the product decision record from saved runs without inventing missing measurements."""
import json
import math
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / 'eval/results'


def read(name):
    return json.loads((RESULTS / name).read_text(encoding='utf-8'))


def wilson(successes, n):
    z = 1.96
    p = successes/n
    d = 1+z*z/n
    middle = (p+z*z/(2*n))/d
    half = z*math.sqrt(p*(1-p)/n+z*z/(4*n*n))/d
    return [round(middle-half,4), round(middle+half,4)]


def run():
    baseline = read('semantic-ablation-dev.json')
    candidate = read('semantic-candidate-dev.json')
    test = read('semantic-test.json')
    english = read('english-test.json')
    provenance = read('comparison-provenance.json')
    for r in (baseline,candidate,test,english):
        if not r.get('complete') or r['metrics']['infraErrors']:
            raise ValueError('Incomplete/failed infrastructure run cannot form a benchmark')
    if not provenance['identicalDevelopmentTasks']:
        raise ValueError('Development inputs differ')
    common = ('model','contextTokens','keepAlive','gpuLayers','releaseEmbeddingBeforeGeneration',
              'semanticCacheEnabled','archiveEnabled','retrievalStrategy','semanticRerankFeatureEnabled','localBackends')
    if any(baseline['configuration'][key] != candidate['configuration'][key] for key in common):
        raise ValueError('Confounded comparison configuration')
    b,c,t,e = [r['metrics'] for r in (baseline,candidate,test,english)]
    overall = read('retrieval-all.json')['metrics']
    heldout_retrieval = read('retrieval-test.json')['metrics']
    before = read('multiturn-before-fact-checks.json')
    after = read('multiturn-after-fact-checks.json')
    units = read('unit-tests.json')
    suites = [s for rows in units.values() for s in rows]
    failures = sum(int(s['failures'])+int(s['errors']) for s in suites)
    total = sum(int(s['tests']) for s in suites)
    passed = total-sum(int(s['skipped']) for s in suites)-failures
    memory = {label:{'afterTrialUsedPercentMin':min(x['memoryAfter']['usedPercent'] for x in run['trials']),
                     'afterTrialUsedPercentMax':max(x['memoryAfter']['usedPercent'] for x in run['trials'])}
              for label,run in (('baseline',baseline),('candidate',candidate),('test',test))}
    groups = {}
    for trial in test['trials']:
        groups.setdefault(trial['id'],[]).append(trial['passed'])
    stable_groups = [values for values in groups.values() if len(values) == 2]
    stable_successes = sum(all(values) for values in stable_groups)
    summary = {'generatedAt':datetime.now(timezone.utc).isoformat(),'decision':'Research prototype; offline engineering gate met, business/human review pending',
        'baseline':b,'candidate':c,'firstFrozenTest':t,'englishExploration':e,'tests':{'run':total,'passed':passed,'failures':failures},
        'modelCallReductionPercent':round((1-c['modelCalls']/b['modelCalls'])*100,1),
        'p95ReductionPercent':round((1-c['p95Ms']/b['p95Ms'])*100,1),
        'testAllTwoTrialsSuccessWilson95':wilson(stable_successes,len(stable_groups)),'memorySnapshots':memory,
        'multiTurnRegressionBefore':before['passed'],'multiTurnRegressionAfter':after['passed'],
        'semanticCorrectness':None,'humanReviewed':False,'businessOutcomeSuccess':None,'embeddingUsage':None}
    (RESULTS/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    body = f'''# AI-Mall Agent 产品评测与迭代结论

2026-10-06 实测。当前结论是**可复查的研发原型**：中文离线工程门槛达标，真实用户与业务专家验证尚未完成。不得把这些成绩写成线上解决率、满意度、营收或零幻觉。

## 同输入的开发集对照

对照为禁用政策直答与人工优先的消融配置，候选启用两条路径；其余模型和资源配置相同。模型为官方 Qwen3 0.6B Q8_0 的本地导入版本，CPU、2048 上下文、生成后释放、检索/生成分阶段驻留；bge-m3 语义召回保留。答案缓存、长期归档和重排语义特征关闭。10 个中文开发任务各运行 2 次，独立会话。

| 指标 | 消融对照 | 最终候选 | 解释 |
|---|---|---|---|
| 任务代理检查 | {b['passed']}/20（{b['taskProxyPassRate']:.0%}） | {c['passed']}/20（{c['taskProxyPassRate']:.0%}） | 必要概念、来源、禁止话术；不是语义正确率 |
| 两次都通过 | 5/10 | 10/10 | 逐任务 pass^2，不能用平均通过率平方替代 |
| 生成调用 | {b['modelCalls']} | {c['modelCalls']} | 降低 {summary['modelCallReductionPercent']}%；不包含 embedding |
| 生成输入/输出 token | {b['inputTokens']} / {b['outputTokens']} | {c['inputTokens']} / {c['outputTokens']} | 不包含 embedding token |
| P50 | {b['p50Ms']/1000:.3f}s | {c['p50Ms']/1000:.3f}s | 同机器与冷加载配置 |
| P95 | {b['p95Ms']/1000:.3f}s | {c['p95Ms']/1000:.3f}s | 降低 {summary['p95ReductionPercent']}%；小样本不能保证线上时延 |

原始报告：[对照](results/semantic-ablation-dev.json)、[候选](results/semantic-candidate-dev.json)、[输入核验](results/comparison-provenance.json)、[模型来源](results/model-manifest.json)。

## 首次冻结测试与探索边界

10 个中文预留任务各 2 次：**{t['passed']}/20（{t['taskProxyPassRate']:.0%}）**，9/10 任务两次都通过。该逐任务稳定性指标的 Wilson 95% 区间约 59.6%–98.2%，样本不足以证明广泛泛化。失败是“我不知道如何才能拿到退款”这类长尾诉求未给出合适的下一步，不能为了好看从分母中删除。

英文探索为 **{e['passed']}/10（{e['taskProxyPassRate']:.0%}）**，每任务只运行一次；暂不承诺英文服务或稳定性。公开样本是 Bitext 合成数据，中文适配及业务判定仍待双语/业务专家复核。源请求及其适配不跨分区，但可能共享 NLG 模板。

首次预留结果：[中文](results/semantic-test.json)、[英文](results/english-test.json)。旧 oracle 的退款时效条件在执行预留任务前修正；中文开发任务未变化，版本与哈希均保留。之后发现并修复的问题归入回归，不能将重复使用的测试集继续称为全新预留集。

## 检索和真实状态回归

149 条自建 gold 全量生产 retrieve API：Recall@5 **{overall['recallAtK']:.2%}**、MRR **{overall['meanReciprocalRank']:.4f}**、拒答词法/结构准确率 **{overall['refusalAccuracy']:.2%}**；{overall['expectedBlockedCount']} 条预期阻断输入全部拒绝，未观测到误阻断，错误 {overall['errorCount']}。这不证明未知攻击全防护。

其中首次本地预留 64 条：Recall@5 **{heldout_retrieval['recallAtK']:.0%}**、MRR **{heldout_retrieval['meanReciprocalRank']:.4f}**。KB 只有 9 篇政策，参考答案由开发者构造，不能当作企业规模的 RAG 证明。单相关来源任务在 Top5 中的 precision 低也可能由多返回来源造成，不能独立判断回答质量。

全 Maven reactor 已完成构建，当前汇总 **{passed}/{total}** 自动化测试通过（包含 5 条专用真实 Redis 集成测试；另有 portal H2 状态测试）。Redis 测试验证 CAS、重启恢复、并发确认、拒绝以及版本发布；portal/model 边界部分模拟，不能冒充完整真实订单/支付 E2E。

匿名修改政策的实测为 HTTP 403，修改前后内容哈希一致：[证据](results/knowledge-write-denial-live.json)。两套前端生产构建通过。GitHub CI 已加入全部模块与 Python 评分器回归；本轮没有声称 Gitee 云端 CI 已运行。

## 评测发现了什么

早期评测因高内存中断，后来发现缓存配置路径错位，原来声称关闭缓存的部分试验并不独立。这些保留为 diagnostic，未纳入正式对照。纯词法模式亦有能力损失，不能默认替换完整召回。

第一版政策直答丢失来源，且小模型虚构发票已生成；该版本代理检查只有 50%，不能接受。最终改为经过版本校验的政策原文摘录，并保留真正使用的来源；订单等实时动作保留工具和确认流程。公开政策不会复用潜在不适当的生成式语义缓存。

三组多轮对话更清楚地证明“来源命中不等于答对”：原答案未回答质量退货运费、称人为损坏免费维修、把同城配送错说为一到两天。尽管来源召回 100%，三个指定事实检查为 **{before['passed']}/3**。补充主题与追问识别后回归为 **{after['passed']}/3**；这是已见失败的窄回归，不是通用语义正确性成绩。[修复前](results/multiturn-before-fact-checks.json)、[修复后](results/multiturn-after-fact-checks.json)。

## 成本、资源与剩余工作

本轮没有云 LLM 或云 judge API 消费；本机算力、电费、折旧、embedding 等成本未知。引用政策降低生成需求，不能由此宣称所有业务推理成本降低 92%。生成模型没有默认替换原生产模型；专用评测模型与历史模型均保留。

开发对照/候选/首次测试的试验结束内存快照分别为 {memory['baseline']['afterTrialUsedPercentMin']}%–{memory['baseline']['afterTrialUsedPercentMax']}%、{memory['candidate']['afterTrialUsedPercentMin']}%–{memory['candidate']['afterTrialUsedPercentMax']}%、{memory['test']['afterTrialUsedPercentMin']}%–{memory['test']['afterTrialUsedPercentMax']}%。快照不是连续峰值采样。分阶段释放适合当前隔离单请求评测，共享并发服务不应直接照搬卸载策略。正常长期归档默认保留，评测关闭归档。

剩余优先级：人工客服实际接入、登录后完整端到端用户任务、长尾退款澄清、引用段落降噪与政策条件一致性复核、真实用户观察、业务专家标注与埋点灰度。当前原文摘录可能冗长，退款起算点等跨文档表述需商家统一；文字与来源一致不能证明政策正确或问题被解决。

简历应突出产品取舍、可靠性与失败迭代，保留当前原型阶段。个人周期、职责、开源来源和 AI 辅助方式需要本人核对；不写无证据的客户规模、营收、CSAT 或上线降本。
'''
    (ROOT/'eval/product-evaluation-report.md').write_text(body,encoding='utf-8')
    print(json.dumps({'tests':summary['tests'],'proxyTestPassRate':t['taskProxyPassRate'],'callReduction':summary['modelCallReductionPercent']}))


if __name__ == '__main__':
    run()
