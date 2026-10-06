"""Check the three observed fact failures; this is regression, not a semantic benchmark."""
import argparse
import json
import re
from pathlib import Path

RULES = {
    'refund-006': (r'质量问题.{0,35}平台承担|平台承担.{0,35}质量问题', 'quality-return freight is paid by platform'),
    'aftersale-006': (r'不在保修范围.{0,35}人为损坏|人为损坏.{0,20}(?:不支持免费|不提供免费|不在保修)', 'human damage excluded from warranty'),
    'shipping-014': (r'同城.{0,25}(?:半日达|四小时|4小时)', 'same-city half-day / four-hour policy'),
}


def run(report_path, output):
    report = json.loads(report_path.read_text(encoding='utf-8'))
    checks = []
    for row in report['cases']:
        pattern, note = RULES[row['id']]
        answer = row.get('response', {}).get('answer', '')
        checks.append({'id':row['id'],'check':note,'passed':bool(re.search(pattern,answer)), 'answer':answer})
    result = {'inputReport':str(report_path),'passed':sum(c['passed'] for c in checks),'cases':checks,
        'scope':'Three observed policy fact failures only; lexical pattern checks, no general semantic correctness or human calibration.'}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':result['passed'],'total':len(checks)}))


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('report',type=Path)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    run(args.report,args.output)
