"""Sync reviewed project documents and evidence; check mirrors without model calls.

Run with the bundled Python and --delivery-dir. --check is read-only.
Only explicitly named deliverables and tracked eval evidence are copied.
No deletion, database operation, credential lookup, or benchmark rewrite.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DOCUMENTS = {
    '实验报告-最终版.md': '实验报告-最终版.md',
    '汇报演讲稿-智能客服RAG系统.md': '汇报演讲稿-智能客服RAG系统.md',
    'eval/product-evaluation-report.md': 'AI-Mall评测报告.md',
    'docs/product/ai-pm-star-project.md': 'AI-Mall_STAR面试稿与AI指挥方法.md',
    'docs/product/interview-evidence-guide.md': 'AI-Mall面试讲解与待补齐项.md',
    'docs/product/recruiter-review.md': 'AI产品经理招聘审查与技术应用.md',
    'docs/product/rag-gap-and-upgrade.md': 'RAG优化与产品取舍.md',
    'docs/product/resume-reference-review.md': 'AI产品经理简历参考与改写依据.md',
    'docs/product/resume-project-section.md': 'AI产品经理项目简历_STAR文字版.md',
    'docs/product/model-selection.md': '智能客服模型选择说明.md',
    'docs/product/chunk-strategy-review.md': 'Chunk分块策略与验证.md',
    'docs/product/document-sync-audit.md': '文档更新核验清单.md',
    'docs/career/role-playbook.md': 'AI-Mall四岗位面试与证据手册.md',
    'docs/career/resume-project-variants.md': 'AI-Mall四岗位简历项目段落.md',
    'docs/career/report-comparison-acceptance.md': '测试报告基线对比与验收.md',
}
# Explicit new evidence can be delivered before a Git commit. Historical evidence
# still follows git ls-files; do not recursively copy arbitrary local outputs.
EXTRA_EVIDENCE = [
    'eval/results/career-readiness/validation.json',
    'eval/results/career-readiness/report-comparison-example.json',
    'eval/results/career-readiness/java-offline.log',
    'eval/results/career-readiness/evaluation-harness.log',
    'eval/results/career-readiness/admin-typecheck.log',
    'eval/results/career-readiness/admin-build.log',
    'eval/results/career-readiness/ui-validation.json',
    'eval/results/career-readiness/comparison-preview.jpg',
    'eval/results/career-readiness/environment-preview.jpg',
    'eval/results/career-readiness/mobile-preview.jpg',
]
COMBINED = ['docs/product/ai-pm-product-brief.md',
            'docs/product/competitive-and-commercial-plan.md']
COMBINED_NAME = 'AI-Mall产品方案与竞争力.md'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def tracked():
    output = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT)
    return [p for p in output.decode('utf-8').split('\0') if p]

def text(path):
    return path.read_text(encoding='utf-8-sig')

def evidence_destination(relative, delivery):
    if relative in {'eval/results/document-sync/validation.json', 'eval/results/document-sync/career-validation.json'}:
        return None  # Avoid including this audit's own JSON in its checksum manifest.
    if relative.startswith('eval/results/'):
        return delivery/'AI-Mall证据'/relative.removeprefix('eval/results/')
    if relative.startswith('eval/archives/'):
        return delivery/'AI-Mall证据/archives'/relative.removeprefix('eval/archives/')
    if relative.startswith('eval/public/'):
        return delivery/'AI-Mall证据/public'/relative.removeprefix('eval/public/')
    if relative.startswith('eval/regression/'):
        return delivery/'AI-Mall证据/regression'/relative.removeprefix('eval/regression/')

def rebase_links(body, source, delivery):
    def replace(match):
        target = match.group(1)
        if re.match(r'^[a-zA-Z]+://', target) or target.startswith('#'):
            return match.group(0)
        path, separator, fragment = target.partition('#')
        resolved = (ROOT/source).parent.joinpath(path).resolve()
        try:
            relative = resolved.relative_to(ROOT).as_posix()
        except ValueError:
            return match.group(0)
        if relative in DOCUMENTS:
            destination = DOCUMENTS[relative]
        elif relative in COMBINED:
            destination = COMBINED_NAME
        else:
            evidence = evidence_destination(relative, delivery)
            destination = evidence.relative_to(delivery).as_posix() if evidence else resolved.as_posix()
        return ']('+destination+(separator+fragment if separator else '')+')'
    return re.sub(r'\]\(([^)]+)\)', replace, body)

def normalized(body):
    return re.sub(r'\s+', '', body.replace('**', '').replace('# ', '').replace('- ', ''))

def audit_career(delivery, synchronize=False):
    """Narrow new-delivery audit; does not weaken the existing full resume audit."""
    issues, mirrors, evidence_records, bodies = [], [], [], []
    for source, name in DOCUMENTS.items():
        if not source.startswith('docs/career/'):
            continue
        expected = rebase_links(text(ROOT/source), source, delivery)
        destination = delivery/name
        if synchronize:
            destination.write_text(expected, encoding='utf-8')
        equal = destination.is_file() and text(destination) == expected
        mirrors.append({'source': source, 'destination': name, 'matches': equal,
                        'sha256': digest(expected.encode('utf-8'))})
        if not equal:
            issues.append({'kind': 'stale_delivery_document', 'path': name})
        bodies.append((name, expected))
    for source in EXTRA_EVIDENCE:
        path = ROOT/source
        if not path.is_file():
            issues.append({'kind': 'missing_source_evidence', 'path': source})
            continue
        data = path.read_bytes()
        destination = evidence_destination(source, delivery)
        if synchronize:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        equal = destination.is_file() and digest(destination.read_bytes()) == digest(data)
        evidence_records.append({'source': source, 'matches': equal, 'sha256': digest(data)})
        if not equal:
            issues.append({'kind': 'stale_evidence', 'path': source})
    for name, body in bodies:
        for match in re.finditer(r'\]\(([^)]+)\)', body):
            target = match.group(1)
            if re.match(r'^[a-zA-Z]+://', target) or target.startswith('#'):
                continue
            if not (delivery/target.split('#')[0]).is_file():
                issues.append({'kind': 'broken_delivery_link', 'path': name, 'target': target})
    # Check actual acceptance state and source hashes, not only copied-byte equality.
    for filename in ['validation.json', 'ui-validation.json']:
        source = 'eval/results/career-readiness/'+filename
        if not (ROOT/source).is_file():
            continue
        validation = json.loads(text(ROOT/source))
        if not validation.get('passed'):
            issues.append({'kind': 'acceptance_failed', 'path': source})
        for relative, checksum in validation.get('sourceSha256', {}).items():
            path = ROOT/relative
            if not path.is_file() or digest(path.read_bytes()) != checksum:
                issues.append({'kind': 'acceptance_source_changed', 'path': relative})
    return {'schemaVersion': 1, 'asOf': '2026-10-07',
            'scope': 'Four-role Markdown deliverables and explicit career evidence; existing PM Word/PDF excluded',
            'deliveryDocuments': mirrors, 'evidence': evidence_records,
            'modelCalls': 0, 'databaseWrites': 0, 'issues': issues, 'passed': not issues}

def audit(delivery, synchronize=False):
    issues = []
    sources = sorted(set(DOCUMENTS) | set(COMBINED) | {'README.md', 'docs/README.md', 'eval/README.md'}
                     | {p for p in tracked() if p.startswith('docs/') and p.endswith('.md')
                        and not p.startswith('docs/knowledge/')})
    source_records = []
    for source in sources:
        path = ROOT/source
        if not path.is_file():
            issues.append({'kind': 'missing_source', 'path': source})
            continue
        body = text(path)
        source_records.append({'path': source, 'sha256': digest(path.read_bytes())})
        # Report locations only; never print a possibly sensitive match.
        for match in re.finditer(r'(?i)(?:\$env:)?[A-Z_]*PASSWORD\s*=\s*[\x27\x22]\d{6,}[\x27\x22]', body):
            issues.append({'kind': 'literal_database_password', 'path': source,
                           'line': body[:match.start()].count('\n')+1})
        # Historical guide asset gaps are reported separately from current deliverables.
        if source in DOCUMENTS or source in COMBINED or source in {'README.md','docs/README.md','eval/README.md'}:
            for match in re.finditer(r'\]\(([^)]+)\)', body):
                target = match.group(1)
                if re.match(r'^[a-zA-Z]+://', target) or target.startswith('#'):
                    continue
                if not (path.parent/target.split('#')[0]).is_file():
                    issues.append({'kind': 'broken_source_link', 'path': source, 'target': target})
    mirrors = []
    for source, name in DOCUMENTS.items():
        expected = rebase_links(text(ROOT/source), source, delivery)
        destination = delivery/name
        if synchronize:
            destination.write_text(expected, encoding='utf-8')
        equal = destination.is_file() and text(destination) == expected
        mirrors.append({'source': source, 'destination': name, 'matches': equal,
                        'sha256': digest(expected.encode('utf-8'))})
        if not equal:
            issues.append({'kind': 'stale_delivery_document', 'path': name})
    combined = '\n\n---\n\n'.join(rebase_links(text(ROOT/p), p, delivery).strip() for p in COMBINED)+'\n'
    destination = delivery/COMBINED_NAME
    if synchronize:
        destination.write_text(combined, encoding='utf-8')
    equal = destination.is_file() and text(destination) == combined
    mirrors.append({'sources': COMBINED, 'destination': COMBINED_NAME, 'matches': equal,
                    'sha256': digest(combined.encode('utf-8'))})
    if not equal:
        issues.append({'kind': 'stale_delivery_document', 'path': COMBINED_NAME})
    evidence_records = []
    for source in sorted(set(tracked()) | set(EXTRA_EVIDENCE)):
        destination = evidence_destination(source, delivery)
        if not destination:
            continue
        data = (ROOT/source).read_bytes()
        # Rebase readable evidence indices; frozen raw benchmark data stays byte-identical.
        if source.endswith('.md'):
            rebased = rebase_links(text(ROOT/source), source, delivery)
            def adjust(match):
                target = match.group(1)
                if re.match(r'^[a-zA-Z]+://', target) or target.startswith('#') or re.match(r'^[A-Z]:/', target):
                    return match.group(0)
                import os
                path, sep, frag = target.partition('#')
                return ']('+Path(os.path.relpath(delivery/path, destination.parent)).as_posix()+(sep+frag if sep else '')+')'
            data = re.sub(r'\]\(([^)]+)\)', adjust, rebased).encode('utf-8')
        if synchronize:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        equal = destination.is_file() and digest(destination.read_bytes()) == digest(data)
        evidence_records.append({'source': source, 'matches': equal,
                                 'rawByteIdenticalRequired': not source.endswith('.md')})
        if not equal:
            issues.append({'kind': 'stale_evidence', 'path': source})
    for mirror in mirrors:
        p = delivery/mirror['destination']
        if p.is_file():
            for match in re.finditer(r'\]\(([^)]+)\)', text(p)):
                target = match.group(1)
                if re.match(r'^[a-zA-Z]+://', target) or target.startswith('#'):
                    continue
                if not (p.parent/target.split('#')[0]).is_file():
                    issues.append({'kind': 'broken_delivery_link', 'path': p.name, 'target': target})
    docx = delivery/'AI产品经理项目简历.docx'
    pdf = delivery/'AI产品经理项目简历.pdf'
    resume_matches = False
    if docx.is_file():
        with zipfile.ZipFile(docx) as package:
            xml = ET.fromstring(package.read('word/document.xml'))
        ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
        content = ''.join(node.text or '' for node in xml.findall('.//w:t', ns))
        resume_matches = normalized(content) == normalized(text(ROOT/'docs/product/resume-project-section.md'))
    if not resume_matches:
        issues.append({'kind': 'stale_docx_resume'})
    pdf_pages = None
    pdf_matches = False
    if pdf.is_file():
        from pypdf import PdfReader
        reader = PdfReader(pdf)
        pdf_pages = len(reader.pages)
        extracted = ''.join(page.extract_text() or '' for page in reader.pages)
        # Compare the reviewed text, so an equivalent rewrite such as "20次中18次"
        # cannot fail merely because an obsolete literal "18/20" disappeared.
        pdf_text = normalized(extracted).replace('\u2022', '').replace('\u25cf', '')
        canonical = text(ROOT/'docs/product/resume-project-section.md')
        pdf_matches = all(normalized(block) in pdf_text for block in canonical.strip().split('\n\n'))
    if not pdf_matches or pdf_pages != 1:
        issues.append({'kind': 'stale_or_wrong_length_pdf_resume', 'pages': pdf_pages})
    report = text(ROOT/'实验报告-最终版.md')
    for marker in ['V1.3', '## 6.1', '## 6.6', '## 15.3', '## 20.3', '# 附录D', 'start-member-secure.ps1', 'policy_section', '18/20', '216', '215']:
        if marker not in report:
            issues.append({'kind': 'missing_final_report_section', 'marker': marker})
    summary = json.loads(text(ROOT/'eval/results/summary.json'))
    chunk = json.loads(text(ROOT/'eval/results/chunk-upgrade/validation.json'))
    if summary['latestVerification']['totalTests'] != 215 or chunk['totalTests'] != 216:
        issues.append({'kind': 'scope_count_mismatch'})
    return {'schemaVersion': 1, 'asOf': '2026-10-07',
            'scope': 'Current reports, product/resume/interview documents, delivery mirrors and tracked evidence; no benchmark rerun',
            'sourceDocuments': source_records, 'deliveryDocuments': mirrors,
            'evidence': evidence_records,
            'resume': {'docxMatchesCanonical': resume_matches, 'pdfMatchesCanonical': pdf_matches, 'pdfPages': pdf_pages,
                       'visualInspection': 'Manual PNG review is separate; see document-sync/resume-page.png and the audit checklist'},
            'modelCalls': 0, 'databaseWrites': 0,
            'issues': issues, 'passed': not issues}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--delivery-dir', type=Path, required=True)
    parser.add_argument('--check', action='store_true', help='Read-only validation; no sync or report writes')
    parser.add_argument('--career-only', action='store_true', help='Only the new four-role documents and acceptance evidence; no existing Word/PDF validation')
    args = parser.parse_args()
    delivery = args.delivery_dir.resolve()
    if not args.check:
        delivery.mkdir(parents=True, exist_ok=True)
    result = (audit_career if args.career_only else audit)(delivery, synchronize=not args.check)
    if not args.check:
        audit_name = 'career-validation.json' if args.career_only else 'validation.json'
        destination = ROOT/'eval/results/document-sync'/audit_name
        destination.parent.mkdir(parents=True, exist_ok=True)
        data = json.dumps(result, ensure_ascii=False, indent=2)+'\n'
        destination.write_text(data, encoding='utf-8')
        mirror = delivery/'AI-Mall证据/document-sync'/audit_name
        mirror.parent.mkdir(parents=True, exist_ok=True)
        mirror.write_text(data, encoding='utf-8')
    print(json.dumps({'passed': result['passed'], 'scope': result['scope'], 'sourceDocuments': len(result.get('sourceDocuments', [])),
                      'deliveryDocuments': len(result['deliveryDocuments']), 'evidenceFiles': len(result['evidence']),
                      'resume': result.get('resume'), 'issues': result['issues']}, ensure_ascii=False))
    raise SystemExit(0 if result['passed'] else 1)

if __name__ == '__main__':
    main()
