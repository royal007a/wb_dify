import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('behavior_report', Path(__file__).resolve().parents[1] / 'behavior_report.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class BehaviorReportTest(unittest.TestCase):
    def test_missing_method_is_not_run_even_when_sibling_passed(self):
        self.assertEqual('not-run', report.outcome(['A#absent'], {'A#present': ['pass']})[0])

    def test_parameterized_partial_and_failure_are_not_success(self):
        for values, expected in [(['pass', 'not-run'], 'not-run'), (['pass', 'fail'], 'fail'), (['pass', 'pass'], 'pass')]:
            self.assertEqual(expected, report.outcome(['A#method'], {'A#method': values})[0])

    def test_empty_selection_never_passes(self):
        self.assertEqual('not-run', report.outcome([], {})[0])

    def test_route_status_does_not_hide_missing_or_failed_assertions(self):
        self.assertEqual('not-run', report.route_status([], {}))
        self.assertEqual('mapped-subcases-only', report.route_status(['A'], {'A': 'pass'}))
        for status in ('fail', 'not-run'):
            self.assertEqual('partial', report.route_status(['A', 'B'], {'A': 'pass', 'B': status}))

    def test_portable_round_trip_and_binding_validation(self):
        observed = {'A#foo': ['pass', 'not-run'], 'A#bar': ['fail']}
        binding = {'p.A': {'xmlSha256': 'abc'}}
        portable = report.portable_input(observed, binding, 'invocation', 'summary-sha')
        self.assertEqual(observed, report.read_portable(portable, binding, 'invocation', 'summary-sha'))
        for field, value in [('invocationId', 'other'), ('testSummarySha256', 'wrong'),
                             ('sourceXmlSha256', {}), ('schemaVersion', 99),
                             ('observed', {'A#foo': ['unknown']})]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                report.read_portable({**portable, field: value}, binding, 'invocation', 'summary-sha')

    def test_xml_parameters_and_flakes_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'TEST-A.xml'
            path.write_text('<testsuite name="p.A"><testcase name="foo(int)[1]"/><testcase name="foo(int)[2]"><flakyFailure/></testcase></testsuite>')
            self.assertEqual({'A#foo': ['pass', 'fail']}, report.observed_cases(path.parent, {'p.A': {'xmlSha256': report.sha(path)}}))

    def test_modified_xml_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'TEST-A.xml'
            path.write_text('<testsuite name="p.A"/>')
            with self.assertRaises(ValueError):
                report.observed_cases(path.parent, {'p.A': {'xmlSha256': 'wrong'}})
