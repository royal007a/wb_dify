import importlib.util
import json
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

    def test_build_round_trip_without_xml_and_missing_method_propagates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / 'evidence'
            xml_dir = evidence / 'reports'
            xml_dir.mkdir(parents=True)
            definitions = root / 'docs/spec'
            definitions.mkdir(parents=True)
            xml = xml_dir / 'TEST-A.xml'
            xml.write_text('<testsuite name="p.A"><properties><property name="private" value="DO-NOT-EXPORT"/></properties>'
                           '<testcase name="foo()"/><system-out>DO-NOT-EXPORT</system-out></testsuite>')
            summary = evidence / 'backend-tests.tests.json'
            summary.write_text(json.dumps({'invocationId': 'run1', 'command': 'synthetic', 'reportDirectory': 'reports',
                                          'tests': {'classes': [{'class': 'p.A', 'xmlSha256': report.sha(xml)}]}}))
            (evidence / 'verification.json').write_text(json.dumps({'invocationId': 'run1', 'headCommit': 'abc', 'result': 'passed',
                                                                  'steps': [{'name': 'backend-tests', 'testSummarySha256': report.sha(summary)}]}))
            mapping = {'cases': [{'caseId': 'CASE', 'tests': ['A#foo']}], 'features': [], 'routes': {'GET /x': ['CASE']}}
            (definitions / 'behavior-cases.json').write_text(json.dumps(mapping))
            (definitions / 'http-api.json').write_text(json.dumps({'endpoints': [{'method': 'GET', 'path': '/x'}]}))
            portable = evidence / 'method-evidence.json'
            original = report.build(root, evidence, export_methods=portable)
            self.assertNotIn('DO-NOT-EXPORT', portable.read_text())
            xml.unlink()  # Only the disposable fixture: proves reconstruction without raw XML.
            self.assertEqual(original, report.build(root, evidence, method_evidence=portable))
            with self.assertRaises(ValueError):
                report.build(root, evidence)  # No silent portable fallback.
            mapping['cases'][0]['tests'] = ['A#renamed']
            (definitions / 'behavior-cases.json').write_text(json.dumps(mapping))
            changed = report.build(root, evidence, method_evidence=portable)
            self.assertEqual('not-run', changed['cases'][0]['status'])
            self.assertEqual('partial', changed['routes'][0]['behaviorStatus'])

    def test_missing_or_duplicate_xml_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with self.assertRaises(ValueError):
                report.observed_cases(directory, {'p.A': {'xmlSha256': 'missing'}})
            xml = directory / 'TEST-A.xml'
            xml.write_text('<testsuite name="p.A"/>')
            (directory / 'TEST-duplicate.xml').write_bytes(xml.read_bytes())
            with self.assertRaises(ValueError):
                report.observed_cases(directory, {'p.A': {'xmlSha256': report.sha(xml)}})
