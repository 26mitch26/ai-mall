"""Calibrate only dev policy/no-answer cases; no changes to production settings."""
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run():
    rows = [json.loads(x) for x in (ROOT / 'agent-customer/src/test/resources/evaluation/customer-gold.jsonl').read_text(encoding='utf-8').splitlines() if x.strip()]
    selected = [c for c in rows if c['split'] == 'dev' and c['category'] in ('policy', 'no_answer')
                and c.get('expectedAction') != 'clarify' and not c.get('followUp')]
    observations = []
    for case in selected:
        request = urllib.request.Request('http://localhost:8083/api/v1/evaluation/retrieve',
            data=json.dumps({'query':case['query'],'topK':5,'modelBudget':0},ensure_ascii=False).encode(),
            headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)['retrieval']
        observations.append({'id':case['id'],'expectedRefusal':case.get('expectedRefusal',False),
            'score':result['topBm25Score'],'hasEvidence':bool(result['documents'])})
    thresholds = []
    for threshold in (1, 1.5, 2, 2.5, 3):
        accepted = lambda row: row['hasEvidence'] and row['score'] >= threshold
        positive = [r for r in observations if not r['expectedRefusal']]
        negative = [r for r in observations if r['expectedRefusal']]
        thresholds.append({'threshold':threshold,'answerable':len(positive),'unanswerable':len(negative),
            'acceptedAnswerable':sum(accepted(r) for r in positive),'falseAccepts':sum(accepted(r) for r in negative)})
    # An all-positive development slice cannot establish a refusal threshold.
    eligible = [r for r in thresholds if r['falseAccepts'] == 0] if negative else []
    best = max(eligible,key=lambda r:(r['acceptedAnswerable'],r['threshold'])) if eligible else None
    report = {'split':'dev','mode':'lexical-only','humanReviewed':False,'observations':observations,
        'thresholds':thresholds,'recommendation':best,'note':'Development evidence calibration only. No recommendation without negative examples. Test split and semantic correctness are not used for tuning.'}
    (ROOT / 'eval/results/bm25-dev-calibration.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'thresholds':thresholds,'recommendation':best}))


if __name__ == '__main__':
    run()
