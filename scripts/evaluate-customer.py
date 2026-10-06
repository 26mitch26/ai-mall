#!/usr/bin/env python3
"""Evaluate customer RAG retrieval or a small, explicitly bounded chat sample."""
import argparse
import json
import math
import os
import statistics
import sys
import time
import uuid
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_DATA = Path(__file__).resolve().parents[1] / "agent-customer/src/test/resources/evaluation/customer-gold.jsonl"


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil((p / 100) * len(ordered)) - 1))
    return round(ordered[index], 2)


def case_query(case):
    return case["query"]


def retrieval_context(case):
    turns = case.get("history", [])
    return "\n".join(f"{turn.get('role', 'user')}: {turn.get('content', '')}" for turn in turns)


def extract_sources(response):
    retrieval = response.get("retrieval") if isinstance(response.get("retrieval"), dict) else {}
    values = response.get("sources") or response.get("documents") or response.get("results") or retrieval.get("documents") or []
    if isinstance(values, dict):
        values = values.get("sources") or values.get("documents") or []
    found = []
    for item in values if isinstance(values, list) else []:
        if isinstance(item, str):
            found.append(item)
        elif isinstance(item, dict):
            source = item.get("source") or item.get("sourceName") or item.get("file")
            if source:
                found.append(str(source))
    return found


def source_key(source):
    return str(source).replace("\\", "/").rsplit("/", 1)[-1].lower()


def score_case(case, response, top_k):
    retrieval = response.get("retrieval") if isinstance(response.get("retrieval"), dict) else {}
    sources = extract_sources(response)[:top_k]
    relevant = case.get("relevantSources", [])
    relevant_keys = {source_key(s) for s in relevant}
    ranked_keys = list(dict.fromkeys(source_key(s) for s in sources))
    hits = [i for i, source in enumerate(ranked_keys) if source in relevant_keys]
    recall = len(set(ranked_keys) & relevant_keys) / len(relevant_keys) if relevant_keys else None
    precision = (len(set(ranked_keys) & relevant_keys) / len(ranked_keys)) if ranked_keys and relevant_keys else (0.0 if relevant_keys else None)
    reciprocal_rank = 1 / (hits[0] + 1) if hits else (0.0 if relevant_keys else None)
    refusal = response.get("refused")
    if refusal is None:
        refusal = response.get("weakEvidence")
    if refusal is None:
        refusal = retrieval.get("weakEvidence")
    if refusal is None and case.get("expectedAction") != "clarify":
        evidence_report = response.get("evidenceReport") or {}
        conflicts = evidence_report.get("conflicts") if isinstance(evidence_report, dict) else None
        if conflicts:
            refusal = True
        else:
            answer = response.get("answer")
            if isinstance(answer, str) and answer:
                refusal = any(phrase in answer for phrase in ("无法回答", "无法确认", "没有找到相关资料", "建议您联系人工客服"))
    return {"recall": recall, "precision": precision, "reciprocalRank": reciprocal_rank,
            "refusalExpected": (bool(case.get("expectedRefusal")) if case.get("expectedAction") != "clarify" else None),
            "refusalActual": refusal if isinstance(refusal, bool) else None,
            "refusalDetection": "weakEvidence/evidenceReport/answer refusal phrases; not a semantic judge",
            "blockedExpected": bool(case.get("expectedBlocked", False)),
            "blockedActual": response.get("_blocked") if isinstance(response.get("_blocked"), bool) else None,
            "sourceHit": bool(hits), "sourceCount": len(sources)}


def aggregate(cases, scored, latencies, mode, response_reports):
    recall = [x["recall"] for x in scored if x["recall"] is not None]
    precision = [x["precision"] for x in scored if x["precision"] is not None]
    mrr = [x["reciprocalRank"] for x in scored if x["reciprocalRank"] is not None]
    refusal_pairs = [(x["refusalExpected"], x["refusalActual"]) for x in scored
                     if x["refusalExpected"] is not None and x["refusalActual"] is not None]
    refusal_accuracy = sum(a == b for a, b in refusal_pairs) / len(refusal_pairs) if refusal_pairs else None
    blocked_pairs = [(x["blockedExpected"], x["blockedActual"]) for x in scored if x["blockedActual"] is not None]
    blocked_accuracy = sum(a == b for a, b in blocked_pairs) / len(blocked_pairs) if blocked_pairs else None
    blocked_positive = [(a, b) for a, b in blocked_pairs if a]
    blocked_negative = [(a, b) for a, b in blocked_pairs if not a]
    traces = []
    for report in response_reports:
        response = report.get("response", {})
        trace = response.get("trace")
        if isinstance(trace, dict): traces.append(trace)
        elif isinstance(response.get("retrieval"), dict) and isinstance(response.get("retrieval").get("trace"), dict):
            traces.append(response["retrieval"]["trace"])
        traces.extend(t for t in report.get("warmupTraces", []) if isinstance(t, dict))
    token_in = sum(t["inputTokens"] for t in traces if isinstance(t.get("inputTokens"), (int, float)))
    token_out = sum(t["outputTokens"] for t in traces if isinstance(t.get("outputTokens"), (int, float)))
    call_counts = [t.get("modelCalls") for t in traces if isinstance(t.get("modelCalls"), (int, float))]
    categories = Counter(c["category"] for c in cases)
    return {
        "mode": mode,
        "sampleCount": len(cases),
        "categoryCounts": dict(sorted(categories.items())),
        "recallAtK": round(statistics.mean(recall), 4) if recall else None,
        "meanReciprocalRank": round(statistics.mean(mrr), 4) if mrr else None,
        "sourcePrecisionAtK": round(statistics.mean(precision), 4) if precision else None,
        "refusalAccuracy": round(refusal_accuracy, 4) if refusal_accuracy is not None else None,
        "refusalScoredCount": len(refusal_pairs),
        "blockedAccuracy": round(blocked_accuracy, 4) if blocked_accuracy is not None else None,
        "blockedScoredCount": len(blocked_pairs),
        "expectedBlockedCount": len(blocked_positive),
        "expectedBlockedRejectRate": (sum(b for _, b in blocked_positive) / len(blocked_positive)) if blocked_positive else None,
        "unexpectedBlockedCount": sum(b for _, b in blocked_negative),
        "latencyMs": {"p50": percentile(latencies, 50), "p95": percentile(latencies, 95)},
        "modelCalls": sum(call_counts) if call_counts else None,
        "inputTokens": token_in if traces and any("inputTokens" in t for t in traces) else None,
        "outputTokens": token_out if traces and any("outputTokens" in t for t in traces) else None,
        "faithfulness": None,
        "faithfulnessNote": "Not measured: this runner has no independent answer-grounding judge. Source overlap is not faithfulness."
    }


def load_cases(path, split):
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return cases if split == "all" else [c for c in cases if c.get("split") == split]


def request_json(url, payload, token, timeout):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def run(args):
    run_id = uuid.uuid4().hex[:12]
    cases = load_cases(args.data, args.split)
    if args.mode == "chat":
        call_budget = args.max_cases or 12
        if call_budget > 12:
            raise ValueError("chat mode is limited to 12 total HTTP calls, including history warmups")
    elif args.max_cases:
        cases = cases[:args.max_cases]

    base = args.base_url.rstrip("/")
    endpoint = base + ("/api/v1/evaluation/retrieve" if args.mode == "retrieval" else "/api/v1/chat")
    token = os.environ.get("AI_MALL_TOKEN")
    latencies, scored, case_reports = [], [], []
    chat_calls = 0
    for case in cases:
        query = case_query(case)
        session_id = "eval-" + run_id + "-" + case["id"]
        payload = {"query": query, "topK": args.top_k, "modelBudget": args.model_budget,
                   "context": retrieval_context(case)} if args.mode == "retrieval" else {
            "message": query, "sessionId": session_id}
        history_questions = [turn.get("content", "") for turn in case.get("history", [])
                             if turn.get("role") == "user" and turn.get("content")]
        if args.mode == "chat" and chat_calls + len(history_questions) + 1 > call_budget:
            break
        warmup_traces = []
        started = time.perf_counter()
        try:
            if args.mode == "chat":
                for history_question in history_questions:
                    warmup_started = time.perf_counter()
                    chat_calls += 1
                    warmup = request_json(endpoint, {"message": history_question, "sessionId": session_id}, token, args.timeout)
                    if isinstance(warmup.get("trace"), dict): warmup_traces.append(warmup["trace"])
            if args.mode == "chat": chat_calls += 1
            response = request_json(endpoint, payload, token, args.timeout)
            response.setdefault("_blocked", False)
            elapsed = (time.perf_counter() - started) * 1000
            latencies.append(elapsed)
            scored_case = score_case(case, response, args.top_k)
            scored.append(scored_case)
            case_reports.append({"id": case["id"], "group": case["group"], "category": case["category"],
                                 "split": case["split"], "latencyMs": round(elapsed, 2), "scores": scored_case,
                                 "response": response, "warmupTraces": warmup_traces})
        except urllib.error.HTTPError as exc:
            blocked = exc.code == 400
            if blocked:
                elapsed = (time.perf_counter() - started) * 1000
                latencies.append(elapsed)
                scored_case = score_case(case, {"_blocked": True}, args.top_k)
                scored.append(scored_case)
                case_reports.append({"id": case["id"], "group": case["group"], "category": case["category"],
                                    "split": case["split"], "httpStatus": exc.code, "latencyMs": round(elapsed, 2),
                                    "scores": scored_case})
            else:
                case_reports.append({"id": case["id"], "group": case["group"], "category": case["category"],
                                    "split": case["split"], "error": f"HTTP {exc.code}"})
        except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as exc:
            case_reports.append({"id": case["id"], "group": case["group"], "category": case["category"],
                                 "split": case["split"], "error": str(exc)})
    successful_ids = {r["id"] for r in case_reports if "scores" in r}
    successful_cases = [c for c in cases if c["id"] in successful_ids]
    successful_scores = [r["scores"] for r in case_reports if "scores" in r]
    successful_reports = [r for r in case_reports if "scores" in r]
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(),
              "annotation": "Developer-constructed cases; business review pending.",
              "splitMethod": "Question intent groups stay wholly in one split to prevent rewrite leakage; all nine KB sources appear in both dev and test. The knowledge base is not split.",
              "split": args.split, "topK": args.top_k,
              "metrics": aggregate(successful_cases, successful_scores, latencies, args.mode, successful_reports),
              "cases": case_reports}
    report["metrics"]["attemptedCount"] = len(case_reports)
    report["metrics"]["errorCount"] = sum("error" in case for case in case_reports)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["metrics"], ensure_ascii=False, indent=2))
    print(f"Report: {args.report}")
    return 2 if any("error" in report for report in case_reports) else 0


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--mode", choices=("retrieval", "chat"), default="retrieval")
    p.add_argument("--split", choices=("dev", "test", "all"), default="test")
    p.add_argument("--base-url", default="http://localhost:8080/agent/customer")
    p.add_argument("--data", type=Path, default=DEFAULT_DATA)
    p.add_argument("--report", type=Path, default=Path("target/customer-evaluation-report.json"))
    p.add_argument("--top-k", type=int, default=5)
    p.add_argument("--max-cases", type=int, default=0, help="Optional case limit in retrieval mode; chat uses this as total HTTP-call budget, default/cap 12")
    p.add_argument("--timeout", type=float, default=30.0)
    p.add_argument("--model-budget", type=int, choices=(0, 1), default=0,
                   help="Retrieval model-call budget: 0 disables decomposition LLM calls; 1 permits at most one")
    return p


if __name__ == "__main__":
    arguments = parser().parse_args()
    if arguments.top_k < 1 or arguments.timeout <= 0:
        parser().error("--top-k and --timeout must be positive")
    if arguments.mode == "chat" and arguments.max_cases == 0:
        arguments.max_cases = 12
    try:
        sys.exit(run(arguments))
    except ValueError as exc:
        parser().error(str(exc))
