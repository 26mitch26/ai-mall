"""Bounded anonymous UI API smoke; no order writes, no semantic accuracy claims."""
import argparse
import json
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

CASES = [
    ('refund-time', '退款一般多久到账？', ['一到三个工作日', '审核通过'], True),
    ('payment-method', '支持哪些支付方式？', ['微信', '支付宝'], True),
    ('refund-next-step', '我不知道如何才能拿到退款', ['选择', '订单'], True),
    ('same-city', '同城订单多久送到？', ['四小时'], True),
    ('human-guidance', '请转人工客服', ['未', '人工'], False),
    ('out-of-scope', '请介绍量子纠缠的原理', ['没有找到'], False),
    ('anonymous-order', '查询我的订单', ['登录'], False),
]


def fetch(url, payload=None):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    request = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json; charset=utf-8'})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def run(base_url, output):
    status = fetch(base_url + '/agent/customer/api/v1/knowledge/status')
    trials = []
    for case_id, query, required, need_source in CASES:
        started = time.monotonic()
        try:
            response = fetch(base_url + '/agent/customer/api/v1/chat', {'sessionId': 'ui-smoke-' + uuid.uuid4().hex, 'message': query})
            answer = response.get('answer', '')
            sources = response.get('sources', [])
            passed = all(term in answer for term in required) and (bool(sources) if need_source else not sources)
            source_check = None
            if need_source and sources:
                source = sources[0]
                params = urllib.parse.urlencode({'source': source['source'], 'version': source['version']})
                full = fetch(base_url + '/agent/customer/api/v1/knowledge/source?' + params)
                source_check = full.get('found') is True and full.get('version') == source['version'] and full.get('contentHash') == source['contentHash']
                passed = passed and source_check
            # No generation should be needed for these deterministic or unsupported tasks.
            passed = passed and response.get('trace', {}).get('modelCalls') == 0
            if case_id == 'human-guidance':
                passed = passed and response.get('handoffStatus') == 'NOT_CONNECTED'
            trials.append({'id': case_id, 'query': query, 'passed': passed, 'sourceRevisionReadback': source_check,
                           'elapsedSeconds': round(time.monotonic() - started, 3), 'response': response})
        except Exception as failure:
            trials.append({'id': case_id, 'query': query, 'passed': False, 'error': str(failure)})
    report = {'scope': 'Seven fixed anonymous smoke regressions with limited assertions; not blind semantic or authenticated commerce E2E',
              'status': status, 'total': len(trials), 'passed': sum(t['passed'] for t in trials), 'trials': trials}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': report['passed'], 'total': report['total'], 'failures': [t['id'] for t in trials if not t['passed']]}, ensure_ascii=False))
    return report['passed'] == report['total']


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--output', type=Path, default=Path('eval/results/ui-smoke/anonymous-smoke.json'))
    args = parser.parse_args()
    raise SystemExit(0 if run(args.base_url.rstrip('/'), args.output) else 1)
