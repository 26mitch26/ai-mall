"""Bounded live HTTP agent eval. Lexical checks are proxies, not semantic correctness."""
import argparse
import hashlib
import json
import math
import os
import statistics
import time
import urllib.error
import urllib.request
import uuid
import ctypes
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


def memory_snapshot():
    if os.name != "nt":
        return None
    class MemoryStatus(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [(key, ctypes.c_ulonglong) for key in
            ("totalPhysical", "availablePhysical", "totalPage", "availablePage", "totalVirtual", "availableVirtual", "extended")]
    status = MemoryStatus()
    status.length = ctypes.sizeof(status)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        raise OSError("Memory status unavailable")
    return {"usedPercent": status.load, "availableMB": round(status.availablePhysical/1024**2)}


def grade(case, response):
    answer = response.get("answer", "")
    if not isinstance(answer, str):
        answer = ""
    source_rows = response.get("sources") or []
    source_rows = source_rows if isinstance(source_rows, list) else []
    sources = {str(s.get("source", "")).replace("\\", "/").rsplit("/", 1)[-1] for s in source_rows if isinstance(s, dict)}
    checks = {"nonempty": bool(answer.strip()),
        "requiredConcepts": all(any(term.lower() in answer.lower() for term in group) for group in case.get("requiredAny", [])),
        "noForbiddenClaims": all(term.lower() not in answer.lower() for term in case.get("forbidden", [])),
        "sourceHit": not case.get("relevantSources") or bool(sources & set(case["relevantSources"]))}
    return {"passed": all(checks.values()), "checks": checks,
        "grader": "deterministic lexical/source proxy; not semantic correctness or actual business-state verification"}


def reliability(trials, k):
    groups = defaultdict(list)
    for trial in trials:
        groups[trial["id"]].append(bool(trial.get("passed", False)))
    eligible = [v for v in groups.values() if len(v) >= k]
    if not eligible:
        return {"k": k, "eligibleTasks": 0, "passAtK": None, "passPowerK": None}
    def comb(n, r):
        return math.comb(n, r) if n >= r else 0
    # Unbiased estimators on n observed trials; pass^k is all k succeed, not pow(mean,k).
    return {"k": k, "eligibleTasks": len(eligible),
        "passAtK": statistics.mean(1 - comb(len(v)-sum(v), k) / comb(len(v), k) for v in eligible),
        "passPowerK": statistics.mean(comb(sum(v), k) / comb(len(v), k) for v in eligible)}


def summarize(trials):
    latencies = sorted(t["latencyMs"] for t in trials)
    traces = [t.get("response", {}).get("trace", {}) for t in trials]
    def measured_sum(key):
        values = [t.get(key) for t in traces if isinstance(t.get(key), (int, float))]
        return sum(values) if len(values) == len(trials) and values else None
    return {"attempts": len(trials), "passed": sum(t.get("passed", False) for t in trials),
        "taskProxyPassRate": statistics.mean(t.get("passed", False) for t in trials) if trials else None,
        "infraErrors": sum("error" in t for t in trials),
        "p50Ms": statistics.median(latencies) if latencies else None,
        "p95Ms": latencies[max(0, math.ceil(len(latencies)*.95)-1)] if latencies else None,
        "modelCalls": measured_sum("modelCalls"), "inputTokens": measured_sum("inputTokens"), "outputTokens": measured_sum("outputTokens"),
        "semanticCorrectness": None, "businessOutcomeSuccess": None, "humanReviewed": False,
        "reliability": [reliability(trials, k) for k in (1, 2, 3)]}


def run(args):
    raw = args.data.read_bytes()
    cases = [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()]
    cases = [c for c in cases if c["split"] == args.split and c["language"] == args.language]
    if args.max_cases:
        cases = cases[:args.max_cases]
    if not cases or len(cases)*args.trials > args.max_http_calls:
        raise ValueError("Empty selection or HTTP budget exceeded; change selection or explicit budget")
    run_id = uuid.uuid4().hex[:12]
    trials = []
    metadata_request = urllib.request.Request(args.base_url.rstrip("/")+"/api/v1/evaluation/configuration", headers={"Accept":"application/json"})
    with urllib.request.urlopen(metadata_request, timeout=args.timeout) as response:
        configuration = json.load(response)
    if configuration.get("model") != args.model or configuration.get("semanticCacheEnabled") is not False or (args.local_only and configuration.get("localBackends") is not True):
        raise ValueError("Model mismatch or answer cache enabled; independent trials refused")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    def save():
        report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "runId": run_id,
            "dataSha256": hashlib.sha256(raw).hexdigest(), "split": args.split, "language": args.language,
            "selectedTasksSha256": hashlib.sha256(json.dumps(cases,ensure_ascii=False,sort_keys=True).encode()).hexdigest(),
            "label": args.label, "trialsPerTask": args.trials, "maxHttpCalls": args.max_http_calls,
            "scheduledAttempts": len(cases)*args.trials, "complete": len(trials) == len(cases)*args.trials,
            "maxMemoryPercent": args.max_memory_percent,
            "baseUrl": args.base_url, "model": args.model, "externalApiSpend": 0 if args.local_only else None,
            "configuration": configuration, "metadataHttpCalls": 1,
            "costNote": "Local compute/electricity costs not measured; no cloud judge calls. Model calls/tokens count generation only, embedding usage is unknown. Cache disabled for independent-trial comparison.",
            "metrics": summarize(trials), "trials": trials}
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
        return report
    for repeat in range(args.trials):
        for case in cases:
            memory = memory_snapshot()
            memory_wait = 0
            while memory and memory["usedPercent"] >= args.max_memory_percent and memory_wait < 15:
                time.sleep(1)  # Give keep_alive=0 time to release the prior trial's allocations.
                memory_wait += 1
                memory = memory_snapshot()
            if memory and memory["usedPercent"] >= args.max_memory_percent:
                save()
                print(f"Resource guard stopped run: {memory}", flush=True)
                return 3
            payload = {"message": case["query"], "sessionId": f"product-eval-{run_id}-{repeat}-{case['id']}"}
            started = time.perf_counter()
            trial = {"id": case["id"], "intent": case["intent"], "trial": repeat+1, "request": payload, "memoryBefore": memory, "resourceWaitSeconds":memory_wait}
            try:
                request = urllib.request.Request(args.base_url.rstrip("/")+"/api/v1/chat",
                    data=json.dumps(payload, ensure_ascii=False).encode(), headers={"Content-Type": "application/json", "Accept":"application/json"}, method="POST")
                with urllib.request.urlopen(request, timeout=args.timeout) as result:
                    trial["response"] = json.load(result)
                if not isinstance(trial["response"], dict):
                    raise ValueError("Unexpected response shape")
                trial.update(grade(case, trial["response"]))
            except (urllib.error.URLError, TimeoutError, ValueError) as exc:
                trial.update({"passed": False, "error": type(exc).__name__})
            trial["latencyMs"] = round((time.perf_counter()-started)*1000, 2)
            trial["memoryAfter"] = memory_snapshot()
            trials.append(trial)
            save()  # Preserve completed evidence even if interrupted.
            print(f"{case['id']} trial={repeat+1} passed={trial['passed']} ms={trial['latencyMs']}", flush=True)
    report = save()
    print(json.dumps(report["metrics"], ensure_ascii=False))
    return 2 if report["metrics"]["infraErrors"] else (1 if report["metrics"]["taskProxyPassRate"] < args.min_pass_rate else 0)


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, default=Path("eval/public/bitext-cases.jsonl"))
    p.add_argument("--base-url", default="http://localhost:8083")
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--split", choices=("dev", "test"), default="test")
    p.add_argument("--language", choices=("en", "zh-adapted"), default="zh-adapted")
    p.add_argument("--trials", type=int, choices=range(1, 6), default=2)
    p.add_argument("--max-cases", type=int, default=0)
    p.add_argument("--max-http-calls", type=int, default=40)
    p.add_argument("--timeout", type=float, default=90)
    p.add_argument("--min-pass-rate", type=float, default=.8)
    p.add_argument("--label", default="candidate")
    p.add_argument("--model", default="qwen3.5-noVL:latest")
    p.add_argument("--local-only", action="store_true")
    p.add_argument("--max-memory-percent", type=int, default=90)
    return p


if __name__ == "__main__":
    p = parser()
    args = p.parse_args()
    if args.max_http_calls < 1 or args.max_cases < 0 or args.timeout <= 0 or not 0 <= args.min_pass_rate <= 1 or not 50 <= args.max_memory_percent <= 95:
        p.error("Invalid budget, timeout or threshold")
    if args.local_only and not args.base_url.startswith(("http://localhost:", "http://127.0.0.1:")):
        p.error("--local-only requires a localhost endpoint")
    try:
        raise SystemExit(run(args))
    except ValueError as exc:
        p.error(str(exc))
