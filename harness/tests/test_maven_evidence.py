import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verification_report import summarize_xml
from maven_step import run


class MavenEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def suite(self, name="example.OneTest", cases="<testcase name='one'/>", tests=1,
              errors=0, failures=0, skipped=0, directory=None):
        path = (directory or self.root) / ("TEST-" + name + ".xml")
        path.write_text(f"<testsuite name='{name}' tests='{tests}' errors='{errors}' "
                        f"failures='{failures}' skipped='{skipped}'>{cases}</testsuite>")
        return path

    def test_all_expected_classes_are_required_even_if_one_passed(self):
        self.suite()
        with self.assertRaisesRegex(ValueError, "missing expected"):
            summarize_xml(self.root, {"example.OneTest": 1, "example.TwoTest": 1})

    def test_error_is_not_failure_and_stdout_does_not_inflate_counts(self):
        self.suite(cases="<testcase name='one'><error/><system-out>Tests run: 100, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1 s -- in fake.Test</system-out></testcase>", errors=1)
        totals = summarize_xml(self.root, {"example.OneTest": 1})["totals"]
        self.assertEqual((1, 0, 1), (totals["tests"], totals["failures"], totals["errors"]))

    def test_rerun_flakes_are_reported_and_missing_testcases_rejected(self):
        self.suite(cases="<testcase name='one'><flakyFailure/><flakyError/></testcase>")
        self.assertEqual(2, summarize_xml(self.root, {"example.OneTest": 1})["totals"]["flakyAttempts"])
        self.suite(tests=2)
        with self.assertRaisesRegex(ValueError, "counts disagree"):
            summarize_xml(self.root, {"example.OneTest": 1})

    def test_disabled_container_is_skip_record_not_expected_method_count(self):
        self.suite(cases="<testcase name='container'><skipped/></testcase>", skipped=1)
        summary = summarize_xml(self.root, {"example.OneTest": 22})
        self.assertEqual(1, summary["totals"]["skipped"])
        self.assertEqual([], summary["underfilledSuites"])
        self.suite()
        self.assertEqual(["example.OneTest"], summarize_xml(self.root, {"example.OneTest": 22})["underfilledSuites"])

    def test_unknown_duplicate_and_malformed_xml_are_rejected(self):
        self.suite()
        with self.assertRaisesRegex(ValueError, "unexpected"):
            summarize_xml(self.root, {"example.OtherTest": 1})
        shutil.copyfile(self.root / "TEST-example.OneTest.xml", self.root / "TEST-duplicate.xml")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            summarize_xml(self.root, {"example.OneTest": 1})

    def setup_step(self):
        (self.root / "harness").mkdir()
        (self.root / "backend").mkdir()
        (self.root / "evidence").mkdir()
        (self.root / "harness/expected-maven-suites.json").write_text(json.dumps({
            "api-inventory": {"example.OneTest": 1}}))
        return self.root / "evidence"

    def test_stale_target_xml_and_green_stdout_cannot_pass(self):
        evidence = self.setup_step()
        stale = self.root / "backend/target/surefire-reports"
        stale.mkdir(parents=True)
        self.suite(directory=stale)
        with patch("maven_step.subprocess.run", return_value=subprocess.CompletedProcess([], 0)):
            self.assertEqual(1, run(self.root, evidence, "api-inventory", "first"))
        self.assertEqual("failed", json.loads((evidence / "api-inventory.tests.json").read_text())["result"])

    def test_fresh_report_passes_then_next_invocation_cannot_reuse_it(self):
        evidence = self.setup_step()
        def fake_mvn(command, **kwargs):
            directory = Path(next(item.split("=", 1)[1] for item in command if item.startswith("-Dhify.test.reportsDirectory=")))
            self.suite(directory=directory)
            return subprocess.CompletedProcess(command, 0)
        with patch("maven_step.subprocess.run", side_effect=fake_mvn):
            self.assertEqual(0, run(self.root, evidence, "api-inventory", "first"))
        first = json.loads((evidence / "api-inventory.tests.json").read_text())
        with patch("maven_step.subprocess.run", return_value=subprocess.CompletedProcess([], 0)):
            self.assertEqual(1, run(self.root, evidence, "api-inventory", "second"))
        second = json.loads((evidence / "api-inventory.tests.json").read_text())
        self.assertNotEqual(first["reportDirectory"], second["reportDirectory"])

    def test_fork_crash_even_after_passing_xml_is_failure(self):
        evidence = self.setup_step()
        def crash(command, **kwargs):
            directory = Path(next(item.split("=", 1)[1] for item in command if item.startswith("-Dhify.test.reportsDirectory=")))
            self.suite(directory=directory)
            return subprocess.CompletedProcess(command, 1)
        with patch("maven_step.subprocess.run", side_effect=crash):
            self.assertEqual(1, run(self.root, evidence, "api-inventory", "crash"))

    def test_backend_source_inventory_matches_reviewed_inventory(self):
        repo = Path(__file__).resolve().parents[2]
        expected = json.loads((repo / "harness/expected-maven-suites.json").read_text())["backend-tests"]
        actual = set()
        for source in (repo / "backend").glob("*/src/test/java/**/*.java"):
            if source.stem.startswith("Test") or source.stem.endswith(("Test", "Tests", "TestCase")):
                actual.add(source.as_posix().split("/src/test/java/", 1)[1][:-5].replace("/", "."))
        self.assertEqual(set(expected), actual)


if __name__ == "__main__":
    unittest.main()
