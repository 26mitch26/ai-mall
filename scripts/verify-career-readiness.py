"""Run the bounded, offline engineering acceptance scope and save fresh evidence.

No API keys, Ollama calls, live MySQL writes, or Docker startup. This is not an
end-to-end commerce or model quality evaluation. Requires Java/Maven, Node/npm,
and installed frontend dependencies. Each invocation replaces this run's report.
"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODULES = ["mall-core/mall-common", "mall-core/mall-security", "mall-portal",
           "agent-customer", "ai-gateway", "agent-test"]


def collect_suites(started):
    suites = []
    for module in MODULES:
        for path in sorted((ROOT / module / "target/surefire-reports").glob("TEST-*.xml")):
            if path.stat().st_mtime < started:
                continue  # Never count a previous run's reports as current evidence.
            suite = ET.parse(path).getroot()
            suites.append({"module": module, "name": suite.get("name"),
                           **{key: int(suite.get(key, "0")) for key in
                              ["tests", "failures", "errors", "skipped"]},
                           "report": path.relative_to(ROOT).as_posix(),
                           "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return suites


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "eval/results/career-readiness")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    maven = shutil.which("mvn")
    npm = shutil.which("npm")
    steps = [
        ("java-offline", [maven or "mvn", "-pl", ",".join(MODULES), "-am", "test",
                          "-Dtest=*,!RagRecallSemanticTest", "-Dsurefire.failIfNoSpecifiedTests=false",
                          "-Dexcluded.groups=benchmark,integration,benchmark-manual",
                          "-Djacoco.skip=true", "--no-transfer-progress", "-q"]),
        ("evaluation-harness", [sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"]),
        ("admin-typecheck", [npm or "npm", "--prefix", "frontend", "run", "type-check"]),
        ("admin-build", [npm or "npm", "--prefix", "frontend", "run", "build-only"]),
    ]
    checks = []
    started = time.time()
    for name, command in steps:
        print(f"Running {name}", flush=True)
        start = time.time()
        log = output / f"{name}.log"
        with log.open("w", encoding="utf-8") as stream:
            try:
                result = subprocess.run(command, cwd=ROOT, stdout=stream,
                                        stderr=subprocess.STDOUT, check=False)
                code = result.returncode
            except OSError as error:
                stream.write(str(error))
                code = -1
        checks.append({"name": name, "command": command, "exitCode": code,
                       "passed": code == 0, "durationSeconds": round(time.time() - start, 3),
                       "log": log.name, "logSha256": hashlib.sha256(log.read_bytes()).hexdigest()})
        print(f"{name}: {'PASS' if code == 0 else 'FAIL'}", flush=True)
    suites = collect_suites(started)
    totals = {key: sum(suite[key] for suite in suites) for key in ["tests", "failures", "errors", "skipped"]}
    missing = [module for module in MODULES if not any(suite["module"] == module for suite in suites)]
    passed = all(check["passed"] for check in checks) and bool(suites) and not missing and not (totals["failures"] or totals["errors"])
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    changed = subprocess.check_output(["git", "status", "--porcelain", "-uall"], cwd=ROOT, text=True, encoding="utf-8").splitlines()
    hashes = {}
    for directory in ["agent-test/src", "frontend/src"]:
        for path in sorted((ROOT / directory).rglob("*")):
            if path.is_file():
                hashes[path.relative_to(ROOT).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    for relative in ["frontend/report-comparison-preview.html", "scripts/verify-career-readiness.py"]:
        hashes[relative] = hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
    report = {"schemaVersion": 1, "asOf": datetime.now(timezone(timedelta(hours=8))).isoformat(),
              "scope": "Offline Java tests, evaluation harness regressions, admin typecheck and production build",
              "head": revision, "workingTreeStatus": changed, "sourceSha256": hashes,
              "excluded": ["RagRecallSemanticTest", "benchmark", "integration", "benchmark-manual"],
              "modelCalls": 0, "liveDatabaseWrites": 0,
              "limitations": ["No live commerce/payment/after-sale end-to-end acceptance",
                              "No live model quality, MySQL concurrency, Redis integration or production load test",
                              "Frontend build does not prove interactive UI behavior"],
              "checks": checks, "java": {**totals, "passed": totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"],
                                         "missingFreshModules": missing, "suites": suites}, "passed": passed}
    (output / "validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": passed, "java": totals, "missingFreshModules": missing}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
