import importlib.util
from pathlib import Path
import unittest
import json

spec = importlib.util.spec_from_file_location("product_eval", Path(__file__).with_name("eval-agent-product.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class EvalProductTest(unittest.TestCase):
    def test_public_languages_and_duplicates_do_not_cross_splits(self):
        rows = [json.loads(line) for line in (Path(__file__).resolve().parents[1]/"eval/public/bitext-cases.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(40, len(rows))
        self.assertEqual(40, len({r["id"] for r in rows}))
        by_instruction = {}
        for row in rows:
            by_instruction.setdefault(row["originalInstruction"].strip().lower(), set()).add(row["split"])
            self.assertEqual("CDLA-Sharing-1.0", row["license"])
        self.assertTrue(all(len(values) == 1 for values in by_instruction.values()))

    def test_null_sources_are_missing_evidence(self):
        self.assertFalse(module.grade({"relevantSources":["policy.md"]}, {"answer":"answer", "sources":None})["passed"])

    def test_any_success_and_all_success_are_distinct(self):
        result = module.reliability([{"id": "a", "passed": True}, {"id": "a", "passed": False}], 2)
        self.assertEqual(1, result["passAtK"])
        self.assertEqual(0, result["passPowerK"])

    def test_errors_remain_in_denominator_and_missing_usage_is_unknown(self):
        result = module.summarize([{"id": "a", "passed": True, "latencyMs": 1, "response": {"trace": {"modelCalls": 1}}},
            {"id": "b", "passed": False, "latencyMs": 2, "error": "TimeoutError"}])
        self.assertEqual(.5, result["taskProxyPassRate"])
        self.assertIsNone(result["modelCalls"])
        self.assertEqual(1, result["infraErrors"])

    def test_fake_transfer_is_not_completion(self):
        result = module.grade({"requiredAny": [["人工"]], "forbidden": ["已转接"]}, {"answer": "已转接人工"})
        self.assertFalse(result["passed"])

    def test_source_overlap_does_not_establish_semantic_correctness(self):
        result = module.summarize([{"id": "a", "passed": True, "latencyMs": 1}])
        self.assertIsNone(result["semanticCorrectness"])
        self.assertIsNone(result["businessOutcomeSuccess"])


if __name__ == "__main__":
    unittest.main()
