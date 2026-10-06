"""Protect report scopes and fixed input provenance; these tests make no model calls."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('report_summary', Path(__file__).with_name('summarize-product-eval.py'))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ReportScopeTest(unittest.TestCase):
    def inputs(self):
        return [report.read(path) for path in ('model-selection/validation.json', 'ui-smoke/anonymous-before-procedure-expansion.json',
            'model-selection/policy-regression.json', 'rag-upgrade/component-before.json', 'rag-upgrade/component-after.json',
            'model-selection/local-probe.json', 'model-selection/policy-selection.json')]

    def test_current_snapshot_keeps_memory_failure_and_non_blind_scope(self):
        snapshot = report.verification_snapshot(*self.inputs())
        self.assertFalse(snapshot['anonymousRegression']['blind'])
        self.assertFalse(snapshot['liveSemanticRun'])
        self.assertIsNone(snapshot['freshSemanticCorrectness'])
        self.assertEqual(snapshot['modelSelection']['localProbe']['code'], 503)
        self.assertEqual(snapshot['totalTests'], 215)

    def test_duplicate_module_cannot_inflate_current_test_total(self):
        inputs = self.inputs()
        inputs[0]['modules'].append(copy.deepcopy(inputs[0]['modules'][0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate modules'):
            report.verification_snapshot(*inputs)

    def test_changed_questions_cannot_be_reported_as_same_input_regression(self):
        inputs = self.inputs()
        inputs[2]['trials'][0]['query'] += ' changed'
        with self.assertRaisesRegex(ValueError, 'inputs differ'):
            report.verification_snapshot(*inputs)

    def test_smoke_pass_count_must_match_raw_trials(self):
        inputs = self.inputs()
        inputs[2]['passed'] -= 1
        with self.assertRaisesRegex(ValueError, 'counts do not reconcile'):
            report.verification_snapshot(*inputs)


if __name__ == '__main__':
    unittest.main()
