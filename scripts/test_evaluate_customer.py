import importlib.util
import unittest
from pathlib import Path
import json
import tempfile
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("evaluate-customer.py")
SPEC = importlib.util.spec_from_file_location("evaluate_customer", MODULE_PATH)
evaluation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluation)


class CustomerEvaluationTest(unittest.TestCase):
    def test_gold_set_has_more_than_one_hundred_cases_and_grouped_splits(self):
        path = MODULE_PATH.parents[1] / "agent-customer/src/test/resources/evaluation/customer-gold.jsonl"
        cases = evaluation.load_cases(path, "all")
        self.assertGreaterEqual(len(cases), 100)
        self.assertGreaterEqual(sum(c["followUp"] for c in cases), 20)
        self.assertTrue(all(len(c.get("history", [])) >= 2 for c in cases if c["followUp"]))
        split_by_group = {}
        splits_by_source = {}
        for case in cases:
            split_by_group.setdefault(case["group"], set()).add(case["split"])
            for source in case["relevantSources"]:
                splits_by_source.setdefault(source, set()).add(case["split"])
        self.assertTrue(all(len(splits) == 1 for splits in split_by_group.values()))
        self.assertEqual(len(splits_by_source), 9)
        self.assertTrue(all(splits == {"dev", "test"} for splits in splits_by_source.values()))

    def test_retrieval_metrics_score_ranked_sources_without_claiming_faithfulness(self):
        case = {"category": "policy", "relevantSources": ["refund-policy.md"], "expectedRefusal": False}
        result = evaluation.score_case(case, {"retrieval": {"documents": [
            {"source": "shipping-policy.md"}, {"source": "refund-policy.md"}], "weakEvidence": False}}, 5)
        self.assertEqual(result["recall"], 1.0)
        self.assertEqual(result["reciprocalRank"], 0.5)
        self.assertTrue(result["sourceHit"])
        metrics = evaluation.aggregate([case], [result], [8, 12], "retrieval", [])
        self.assertIsNone(metrics["faithfulness"])
        self.assertEqual(metrics["latencyMs"]["p50"], 8)

    def test_source_precision_uses_unique_sources_not_chunk_count(self):
        case = {"category": "policy", "relevantSources": ["refund-policy.md"]}
        result = evaluation.score_case(case, {"documents": [
            {"source": "refund-policy.md"}, {"source": "refund-policy.md"}]}, 5)
        self.assertEqual(result["precision"], 1.0)

    def test_chat_followup_runs_real_warmup_and_followup_under_total_call_budget(self):
        record = {"id": "followup-1", "group": "refund", "category": "policy", "split": "test",
                  "query": "那运费谁承担？", "referenceAnswer": "...", "relevantSources": ["refund-policy.md"],
                  "expectedRefusal": False, "expectedBlocked": False, "expectedAction": "answer",
                  "followUp": True, "history": [{"role": "user", "content": "质量问题退货怎么处理？"},
                                                  {"role": "assistant", "content": "質量問題退貨由平台承擔運費。"}]}
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / "gold.jsonl"
            report = Path(directory) / "report.json"
            data.write_text(json.dumps(record, ensure_ascii=False) + "\n", encoding="utf-8")
            args = evaluation.parser().parse_args(["--mode", "chat", "--data", str(data), "--report", str(report)])
            fake = {"answer": "由平台承担", "sources": [{"source": "refund-policy.md"}],
                    "trace": {"modelCalls": 1, "inputTokens": 2, "outputTokens": 3}}
            with patch.object(evaluation, "request_json", return_value=fake) as request:
                self.assertEqual(evaluation.run(args), 0)
            self.assertEqual(request.call_count, 2)
            self.assertEqual(request.call_args_list[0].args[1]["message"], "质量问题退货怎么处理？")
            self.assertEqual(request.call_args_list[1].args[1]["message"], "那运费谁承担？")
            result = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(result["metrics"]["modelCalls"], 2)
            self.assertEqual(result["metrics"]["inputTokens"], 4)

    def test_unmeasured_refusal_and_model_tokens_are_null(self):
        case = {"category": "no_answer", "relevantSources": [], "expectedRefusal": True}
        result = evaluation.score_case(case, {"retrieval": {"documents": []}}, 5)
        metrics = evaluation.aggregate([case], [result], [], "retrieval", [])
        self.assertIsNone(metrics["refusalAccuracy"])
        self.assertIsNone(metrics["inputTokens"])
        self.assertIsNone(metrics["outputTokens"])


if __name__ == "__main__":
    unittest.main()
