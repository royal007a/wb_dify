import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPORT = Path(__file__).resolve().parents[1] / "verification_report.py"


class VerificationReportTest(unittest.TestCase):
    def run_report(self, log, exit_code=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "backend.log").write_text(log, encoding="utf-8")
            source = {"schemaVersion": 1, "headCommit": "source-commit",
                      "result": "passed", "scopes": ["backend"], "steps": [
                          {"name": "backend-tests", "exitCode": exit_code,
                           "log": "backend.log", "command": ["mvn", "test"]}]}
            original = json.dumps(source)
            (root / "verification.json").write_text(original, encoding="utf-8")
            result = subprocess.run([sys.executable, str(REPORT), "--root", str(root),
                                     "--verification", str(root / "verification.json"),
                                     "--output", str(root / "summary.json")], capture_output=True, text=True)
            self.assertTrue((root / "summary.json").exists(), result.stderr)
            self.assertEqual(original, (root / "verification.json").read_text())
            return result.returncode, json.loads((root / "summary.json").read_text())

    def test_skips_are_partial_and_fail_gate_without_rewriting_history(self):
        code, report = self.run_report(
            "\x1b[0m[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 2, Time elapsed: 1 s -- in com.example.PgTest\n"
            "[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 2\n")
        self.assertNotEqual(0, code)
        self.assertEqual("partial", report["result"])
        self.assertEqual("passed", report["commandResult"])
        tests = report["steps"][0]["mavenTests"]
        self.assertEqual({"tests": 5, "executed": 3, "failures": 0, "errors": 0, "skipped": 2}, tests["totals"])
        self.assertEqual("source-commit", report["headCommit"])
        self.assertEqual(64, len(report["steps"][0]["logSha256"]))

    def test_green_per_class_counts_do_not_double_count_reactor_totals(self):
        code, report = self.run_report(
            "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in example.OneTest\n"
            "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\n"
            "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.2 s -- in example.TwoTest\n")
        self.assertEqual(0, code)
        self.assertEqual("passed", report["result"])
        self.assertEqual(5, report["steps"][0]["mavenTests"]["totals"]["tests"])
        self.assertEqual(2, len(report["steps"][0]["mavenTests"]["classes"]))

    def test_empty_test_log_cannot_pass(self):
        code, report = self.run_report("BUILD SUCCESS\n")
        self.assertNotEqual(0, code)
        self.assertEqual("failed", report["result"])

    def test_test_failure_wins_even_when_command_claims_zero(self):
        code, report = self.run_report("Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1 s -- in X\n")
        self.assertNotEqual(0, code)
        self.assertEqual("failed", report["result"])

    def test_nonzero_command_cannot_be_overruled_by_green_test_counts(self):
        code, report = self.run_report("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1 s -- in X\n", 1)
        self.assertNotEqual(0, code)
        self.assertEqual("failed", report["result"])

    def test_missing_log_fails_and_non_maven_scope_has_no_invented_test_counts(self):
        spec = importlib.util.spec_from_file_location("verification_report", REPORT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {"steps": [{"name": "frontend-build", "exitCode": 0, "log": "build.log"}]}
            self.assertEqual("failed", module.enrich(manifest, root)["result"])
            (root / "build.log").write_text("build ok")
            report = module.enrich(manifest, root)
            self.assertEqual("passed", report["result"])
            self.assertEqual("not-assessed", report["testCoverage"])
            self.assertNotIn("mavenTests", report["steps"][0])

    def test_shell_gate_rejects_successful_maven_with_skips(self):
        self.assert_shell_gate(skipped=1, expected=1)

    def test_shell_gate_accepts_zero_skip_observed_tests(self):
        self.assert_shell_gate(skipped=0, expected=0)

    def assert_shell_gate(self, skipped, expected):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for folder in ("harness", "backend", "bin"):
                (root / folder).mkdir()
            script = REPORT.with_name("verify.sh").read_text()
            if os.environ.get("VERIFY_TEST_OLD_COMMIT"):
                script = subprocess.run(["git", "show", os.environ["VERIFY_TEST_OLD_COMMIT"] + ":harness/verify.sh"],
                                        cwd=REPORT.parent, check=True, capture_output=True, text=True).stdout
            (root / "harness/verify.sh").write_text(script)
            shutil.copyfile(REPORT, root / "harness/verification_report.py")
            for name, body in {"docker": "exit 1", "mvn":
                "printf '%s\\n' 'Tests run: 2, Failures: 0, Errors: 0, Skipped: " + str(skipped) +
                ", Time elapsed: 1 s -- in example.IsolatedTest'\nexit 0"}.items():
                path = root / "bin" / name
                path.write_text("#!/bin/sh\n" + body + "\n")
                path.chmod(0o755)
            env = dict(os.environ, PATH=str(root / "bin") + os.pathsep + os.environ["PATH"])
            result = subprocess.run(["sh", str(root / "harness/verify.sh"), "--scope", "backend",
                                     "--evidence-dir", "harness/evidence/test"],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
            report = json.loads((root / "harness/evidence/test/verification.json").read_text())
            self.assertEqual("partial" if skipped else "passed", report["result"])
            self.assertEqual(skipped, report["steps"][0]["mavenTests"]["totals"]["skipped"])


if __name__ == "__main__":
    unittest.main()
