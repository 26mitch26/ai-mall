"""Build the product decision record from saved runs without inventing missing measurements."""
import json
import math
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / 'eval/results'


def read(name):
    return json.loads((RESULTS / name).read_text(encoding='utf-8-sig'))


def verification_snapshot(validation, before, after, component_before, component_after, probe, selection):
    """Keep scoped regressions separate from archived model benchmarks."""
    names = [row['name'] for row in validation['modules']]
    if len(names) != len(set(names)):
        raise ValueError('Duplicate modules would inflate current test count')
    for report in (before, after):
        if report['total'] != len(report['trials']) or report['passed'] != sum(t['passed'] for t in report['trials']):
            raise ValueError('Smoke counts do not reconcile with trials')
    signature = lambda report: sorted((t['id'], t['query']) for t in report['trials'])
    if signature(before) != signature(after):
        raise ValueError('Smoke before/after inputs differ')
    if sorted(c['id'] for c in component_before['cases']) != sorted(c['id'] for c in component_after['cases']):
        raise ValueError('Component regression cases differ')
    for component in (component_before, component_after):
        if component['total'] != len(component['cases']) or component['passed'] != sum(c['passed'] for c in component['cases']):
            raise ValueError('Component counts do not reconcile')
    return {
        'asOf': validation['date'], 'scope': 'Three-module software and known anonymous regressions; not a fresh full-reactor or semantic benchmark',
        'modules': validation['modules'], 'totalTests': sum(int(row['tests']) for row in validation['modules']),
        'failures': validation['failures'], 'errors': validation['errors'], 'skipped': validation['skipped'],
        'liveSemanticRun': validation['liveSemanticRun'], 'realCloudCalls': validation['realCloudCalls'],
        'frontend': {key: validation[key] for key in ('adminTypeCheck', 'adminBuild', 'memberH5Build')},
        'componentRegression': {'before': component_before['passed'], 'after': component_after['passed'], 'total': component_after['total'], 'blind': False},
        'anonymousRegression': {'before': before['passed'], 'after': after['passed'], 'total': after['total'], 'blind': False,
                                'inputSignaturesMatch': True, 'generationCalls': sum(t.get('response', {}).get('trace', {}).get('modelCalls', 0) for t in after['trials']),
                                'configuration': after['status']},
        'modelSelection': {'installed': validation['installedModels'], 'chatCapable': validation['chatModels'],
                           'selectedModel': selection.get('selectedModel'), 'generationUsedInSelectionCheck': selection.get('generationUsed'),
                           'localProbe': probe, 'transportEvidence': 'Mock HTTP tests; live metadata/selection/guard checks'},
        'freshSemanticCorrectness': None, 'businessOutcomeSuccess': None, 'humanReviewed': False,
        'sources': ['model-selection/validation.json', 'model-selection/policy-regression.json', 'model-selection/local-probe.json',
                    'model-selection/policy-selection.json', 'ui-smoke/anonymous-before-procedure-expansion.json',
                    'rag-upgrade/component-before.json', 'rag-upgrade/component-after.json'],
    }


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
    latest = verification_snapshot(read('model-selection/validation.json'), read('ui-smoke/anonymous-before-procedure-expansion.json'),
        read('model-selection/policy-regression.json'), read('rag-upgrade/component-before.json'), read('rag-upgrade/component-after.json'),
        read('model-selection/local-probe.json'), read('model-selection/policy-selection.json'))
    login = read('member-login-verification.json')
    latest['memberLoginReadOnly'] = login
    latest['sources'].append('member-login-verification.json')
    chunk = read('chunk-upgrade/validation.json') if (RESULTS/'chunk-upgrade/validation.json').exists() else None
    boundary = read('chunk-upgrade/boundary-experiment.json') if chunk else None
    summary = {'schemaVersion':2,'generatedAt':datetime.now(timezone.utc).isoformat(),'reportAsOf':latest['asOf'],
        'decision':'Research prototype; archived proxy benchmark and current scoped regressions available; fresh semantic and business review pending',
        'historicalBenchmarkDate':'2026-10-06','latestVerification':latest,
        'baseline':b,'candidate':c,'firstFrozenTest':t,'englishExploration':e,
        'tests':{'run':total,'passed':passed,'failures':failures,'scope':'Archived full-reactor snapshot, 2026-10-06; not current scoped tests'},
        'modelCallReductionPercent':round((1-c['modelCalls']/b['modelCalls'])*100,1),
        'p95ReductionPercent':round((1-c['p95Ms']/b['p95Ms'])*100,1),
        'testAllTwoTrialsSuccessWilson95':wilson(stable_successes,len(stable_groups)),'memorySnapshots':memory,
        'multiTurnRegressionBefore':before['passed'],'multiTurnRegressionAfter':after['passed'],
        'semanticCorrectness':None,'humanReviewed':False,'businessOutcomeSuccess':None,'embeddingUsage':None}
    if chunk:
        summary['chunkVerification'] = {'validation':chunk,'boundaryExperiment':boundary,
                                      'scope':'Synthetic structural coverage; not a blind retrieval or answer-quality benchmark'}
    (RESULTS/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    module_labels = '、'.join(f"{row['name']} {row['tests']}" for row in latest['modules'])
    config = latest['anonymousRegression']['configuration']
    probe_note = (f"HTTP {latest['modelSelection']['localProbe'].get('code')}：{latest['modelSelection']['localProbe'].get('message')}"
                  if latest['modelSelection']['localProbe'].get('status') == 'guarded-or-unavailable'
                  else '按本地试测原始记录核对；不代表模型质量评测')
    body = f'''# AI-Mall Agent 产品评测与迭代结论

报告证据截至 **{latest['asOf']}**。当前结论是**可复查的研发原型**：历史中文任务代理门槛达标，后续版本通过了有限软件与访客回归；真实用户、业务专家和新版本完整语义验证尚未完成。不得把这些成绩写成线上解决率、满意度、营收或零幻觉。

## 最近完成的验证记录

| 项目 | 最新证据 | 能证明什么与限制 |
|---|---|---|
| 模型选择阶段软件测试 | {latest['totalTests']}项，失败{latest['failures']}、错误{latest['errors']}、跳过{latest['skipped']} | {module_labels}；保留该阶段范围，不能与后续分块阶段相加 |
| 政策组件回归 | {latest['componentRegression']['before']}/12 → {latest['componentRegression']['after']}/12 | 已检索资料的选段、来源、数值和路由断言；不是端到端准确率 |
| 访客界面/API回归 | {latest['anonymousRegression']['before']}/7 → {latest['anonymousRegression']['after']}/7，生成调用{latest['anonymousRegression']['generationCalls']} | 输入一致的已知场景，包含来源版本读取；不是新的独立盲测 |
| 当前演示模式 | {config['retrieval']}；{config['knowledgeBase']['documents']}篇政策；embedding：{config['embeddingModel']}，向量库：{config['vectorStore']} | 与历史语义对照配置不同，不能直接比较成绩或时延 |
| 本地模型选择 | {latest['modelSelection']['installed']}个已安装模型，{latest['modelSelection']['chatCapable']}个聊天模型；选择字段回传正确 | 请求参数通过模拟HTTP测试检查；选择检查的政策答复没有调用生成 |
| 本地短回复试测 | {probe_note} | 保留未完成结果；不能写成新模型生成质量验证成功 |
| 可选云端接口 | 协议、Key脱敏和地址约束通过模拟测试；真实云调用{latest['realCloudCalls']}次 | 仅提供配置入口，没有真实供应商可用性或费用评测 |
| 前端 | 管理端类型检查/构建、会员端H5构建通过 | 访客界面已验证；登录后的商品、订单、支付链路仍待完整实测 |
| 会员登录恢复 | 登录{login['loginCode']}、会员信息{login['memberInfoCode']}、订单列表{login['orderReadCode']} | 2026-10-07通过原有会员账号只读验证；不证明下单、支付或售后提交完成 |

依据：[验证记录](results/model-selection/validation.json)、[访客回归](results/model-selection/policy-regression.json)、[本地试测暂停](results/model-selection/local-probe.json)、[界面截图](results/model-selection/model-settings.jpg)。本次仅汇总已有记录，没有重新运行模型评测。各阶段测试数量不能叠加，12项组件断言也不能加到软件测试总数中。

会员登录排障发现8087服务未运行，随后启动时缺少本机数据库密码注入。用户提供连接凭据后，原有demo账号登录及受保护的会员信息、订单列表读取均成功；没有修改账号密码、重置数据库或执行交易写入。[登录只读验证](results/member-login-verification.json)。数据库密码只临时注入进程环境，没有写入报告、README或证据。

## 2026年10月6日的历史开发集对照

以下是历史实验快照。后续BM25、选段和模型切换改动后，没有重新测出同配置的新成绩。

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

以下首次成绩保留在2026-10-06的运行记录内，不会因后续修复回写。Wilson区间按10个任务组计算，依赖样本独立等假设；合成任务不是线上随机客户样本，20次试验也不是20个独立用户。

10 个中文预留任务各 2 次：**{t['passed']}/20（{t['taskProxyPassRate']:.0%}）**，9/10 任务两次都通过。该逐任务稳定性指标的 Wilson 95% 区间约 59.6%–98.2%，样本不足以证明广泛泛化。失败是“我不知道如何才能拿到退款”这类长尾诉求未给出合适的下一步，不能为了好看从分母中删除。

英文探索为 **{e['passed']}/10（{e['taskProxyPassRate']:.0%}）**，每任务只运行一次；暂不承诺英文服务或稳定性。公开样本是 Bitext 合成数据，中文适配及业务判定仍待双语/业务专家复核。源请求及其适配不跨分区，但可能共享 NLG 模板。

首次预留结果：[中文](results/semantic-test.json)、[英文](results/english-test.json)。旧 oracle 的退款时效条件在执行预留任务前修正；中文开发任务未变化，版本与哈希均保留。之后发现并修复的问题归入回归，不能将重复使用的测试集继续称为全新预留集。

## 检索和真实状态回归

本节为2026-10-06的历史语义检索与状态证据；当前BM25界面回归没有重新测量下面的149条全量召回指标。

149 条自建 gold 全量生产 retrieve API：Recall@5 **{overall['recallAtK']:.2%}**、MRR **{overall['meanReciprocalRank']:.4f}**、拒答词法/结构准确率 **{overall['refusalAccuracy']:.2%}**；{overall['expectedBlockedCount']} 条预期阻断输入全部拒绝，未观测到误阻断，错误 {overall['errorCount']}。这不证明未知攻击全防护。

其中首次本地预留 64 条：Recall@5 **{heldout_retrieval['recallAtK']:.0%}**、MRR **{heldout_retrieval['meanReciprocalRank']:.4f}**。KB 只有 9 篇政策，参考答案由开发者构造，不能当作企业规模的 RAG 证明。单相关来源任务在 Top5 中的 precision 低也可能由多返回来源造成，不能独立判断回答质量。

2026-10-06存档的全 Maven reactor 汇总为 **{passed}/{total}** 自动化测试通过（包含5条专用真实Redis集成测试，另有portal H2状态测试）。这不是当前215项相关测试的另一部分，不能相加。Redis覆盖CAS、重启恢复、并发确认、拒绝及版本发布；portal/model部分边界模拟，不能冒充完整真实订单/支付E2E。

匿名修改政策的实测为 HTTP 403，修改前后内容哈希一致：[证据](results/knowledge-write-denial-live.json)。两套前端生产构建通过。GitHub CI 已加入全部模块与 Python 评分器回归；本轮没有声称 Gitee 云端 CI 已运行。

## 评测发现了什么

后续回修补充：12项已检索资料组件检查从3/12到12/12，保护数字条件、切题选段和来源对应。2026-10-07的真实界面又发现退款误拒答及引用验收条款的离题回答；修复BM25正权重、历史版本df污染、低资源排序及时效片段后，7项访客冒烟仍有退款步骤失败（6/7），补充公开流程问法扩展后为7/7。来源命中、组件测试通过和“有回复”都不足以独立证明任务完成。[组件记录](results/rag-upgrade/component-after.json)、[访客修复前](results/ui-smoke/anonymous-before-procedure-expansion.json)、[当前访客回归](results/model-selection/policy-regression.json)。

上下文预算保留完整规则、当前问题和工具结果，必需内容超限就停止；字符数不是精确token预算。模型选择按请求隔离，云端只提供兼容入口。配置/API形状通过测试，不等于已证明任意模型或云供应商能可靠完成商城任务；本地真实试答暂停是当前资源限制，不能删除或计作成功。

早期评测因高内存中断，后来发现缓存配置路径错位，原来声称关闭缓存的部分试验并不独立。这些保留为 diagnostic，未纳入正式对照。纯词法模式亦有能力损失，不能默认替换完整召回。

第一版政策直答丢失来源，且小模型虚构发票已生成；该版本代理检查只有 50%，不能接受。最终改为经过版本校验的政策原文摘录，并保留真正使用的来源；订单等实时动作保留工具和确认流程。公开政策不会复用潜在不适当的生成式语义缓存。

三组多轮对话更清楚地证明“来源命中不等于答对”：原答案未回答质量退货运费、称人为损坏免费维修、把同城配送错说为一到两天。尽管来源召回 100%，三个指定事实检查为 **{before['passed']}/3**。补充主题与追问识别后回归为 **{after['passed']}/3**；这是已见失败的窄回归，不是通用语义正确性成绩。[修复前](results/multiturn-before-fact-checks.json)、[修复后](results/multiturn-after-fact-checks.json)。

## 成本、资源与剩余工作

历史对照没有云LLM或云judge API消费；后续界面回归的生成调用为0，云端仍只有模拟验证。本次报告刷新不执行推理。本机算力、电费、折旧和embedding等成本未知；92%只对应历史开发对照的文本调用减少，不是总成本或财务降本。原生产默认与专用评测模型区分，权重均保留；当前演示的默认模型以本节最新配置记录为准。

开发对照/候选/首次测试的试验结束内存快照分别为 {memory['baseline']['afterTrialUsedPercentMin']}%–{memory['baseline']['afterTrialUsedPercentMax']}%、{memory['candidate']['afterTrialUsedPercentMin']}%–{memory['candidate']['afterTrialUsedPercentMax']}%、{memory['test']['afterTrialUsedPercentMin']}%–{memory['test']['afterTrialUsedPercentMax']}%。快照不是连续峰值采样。分阶段释放适合当前隔离单请求评测，共享并发服务不应直接照搬卸载策略。正常长期归档默认保留，评测关闭归档。

剩余优先级：冻结一批尚未用于回修的新能力样本；补业务专家校准与真实用户观察；在已恢复的登录态下补齐商品/订单操作、支付、售后提交链路、真实人工接收回执和埋点灰度。已有登录与只读成功，不等于交易闭环。已知退款问法和选段已回归，不代表所有长尾都覆盖。退款起算点等政策条件仍需商家统一，原句一致不证明适用于具体订单。新模型的真实生成质量、时延、内存峰值及完整成功成本需另测，不能用模型列表读取或内存暂停代替。

简历应突出产品取舍、可靠性与失败迭代，保留当前原型阶段。个人周期、职责、开源来源和 AI 辅助方式需要本人核对；不写无证据的客户规模、营收、CSAT 或上线降本。
'''
    if chunk:
        selected_rows = {r['strategy']:r for r in boundary['rows'] if r['targetCharacters']==512 or r['strategy']=='whole-document'}
        fixed,sentence,policy,whole = (selected_rows[name] for name in ('fixed_size','sentence','policy_section','whole-document'))
        chunk_modules = '、'.join(f"{m['name']} {m['tests']}" for m in chunk['modules'])
        body += f'''\n## 分块策略后续验证\n\n分块阶段客服/公共模块{chunk['totalTests']}项测试通过（{chunk_modules}），与上面的模型选择阶段不是同一范围，不能累加。管理端新增并推荐policy_section；当前共享资料未重建，保留历史输入和版本。\n\n在{boundary['corpusCharacters']}字符、{boundary['rulePairs']}组规则/例外的合成夹具上，固定512保留{fixed['completeRulePairs']}/{boundary['rulePairs']}完整组、按句512为{sentence['completeRulePairs']}/{boundary['rulePairs']}、政策章节策略为{policy['completeRulePairs']}/{boundary['rulePairs']}；整篇不切也为{whole['completeRulePairs']}/{boundary['rulePairs']}，但上下文为{whole['maxCharacters']}字符。政策512产生{policy['chunks']}块，平均{policy['averageCharacters']:.1f}、最大{policy['maxCharacters']}字符；完整结构优先可能超过目标，因此证据不证明512最优或RAG回答准确率提升。\n\n新增保护还覆盖连续段落、表格/代码围栏、标题上下文、选段阶段例外保留、同来源多块证据及策略变化的新版本。过长原文不再截前半段，可能增加拒答；真实召回、token预算与语义正确率需在新独立样本验证。Late Chunking和真实Semantic Chunking尚未实现，不写成已采用技术。[原始结构实验](results/chunk-upgrade/boundary-experiment.json)、[验证范围](results/chunk-upgrade/validation.json)、[策略取舍](../docs/product/chunk-strategy-review.md)。\n'''
    (ROOT/'eval/product-evaluation-report.md').write_text(body,encoding='utf-8')
    print(json.dumps({'historicalTests':summary['tests'],'latestScopedTests':latest['totalTests'],
                      'historicalProxyTestPassRate':t['taskProxyPassRate'],'historicalCallReduction':summary['modelCallReductionPercent']}))


if __name__ == '__main__':
    run()
