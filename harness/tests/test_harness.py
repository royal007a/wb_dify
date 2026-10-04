import json
import copy
import hashlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from harness import HarnessStore  # noqa: E402


class HarnessStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "harness").mkdir()
        (self.root / "exec-plans" / "active").mkdir(parents=True)
        (self.root / "exec-plans" / "active" / "TEST-001.md").write_text("# Plan\n", encoding="utf-8")
        self.write("harness/permissions.yaml", {
            "schemaVersion": 1,
            "riskLevels": {
                "read_only": {"allowed": True, "requiresApproval": False},
                "reversible_write": {"allowed": True, "requiresApproval": False},
                "high_risk": {"allowed": True, "requiresApproval": True},
                "prohibited": {"allowed": False, "requiresApproval": True},
            },
            "operations": {"inspect": "read_only", "deployment": "high_risk"},
        })
        self.write("harness/tasks.json", {
            "schemaVersion": 1,
            "project": "hify",
            "updatedAt": "2026-09-19T00:00:00Z",
            "tasks": [self.task("TEST-001", "inspect", "read_only")],
        })
        self.write("harness/state.json", self.idle_state())
        self.write("harness/legacy-evidence.json", {"schemaVersion": 1, "tasks": {}})
        self.store = HarnessStore(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def idle_state(self):
        return {
            "schemaVersion": 1,
            "currentTaskId": None,
            "runId": None,
            "baselineCommit": None,
            "startedAt": None,
            "evidencePath": None,
            "lastCheckpoint": None,
            "lastVerification": None,
            "lastCompletedTaskId": None,
            "updatedAt": "2026-09-19T00:00:00Z",
        }

    def task(self, task_id, operation, risk):
        return {
            "id": task_id,
            "title": "test",
            "description": "test task",
            "priority": "P0",
            "status": "pending",
            "scopes": ["harness"],
            "verifyScopes": ["harness"],
            "risk": risk,
            "operation": operation,
            "approvalRef": None,
            "dependsOn": [],
            "acceptance": ["works"],
            "planPath": "exec-plans/active/TEST-001.md",
            "checkpoint": None,
            "blockedReason": None,
            "evidence": [],
            "createdAt": "2026-09-19T00:00:00Z",
            "updatedAt": "2026-09-19T00:00:00Z",
        }

    def test_lifecycle_writes_evidence_moves_plan_and_renders_progress(self):
        self.assertEqual([], self.store.validate())
        evidence = self.store.start("TEST-001", None)
        self.assertTrue((self.root / evidence / "run.json").exists())
        with self.assertRaisesRegex(ValueError, "already running"):
            self.store.start("TEST-001", None)
        self.store.checkpoint("TEST-001", "halfway")
        self.write_verification(evidence)
        self.store.finish("TEST-001", "completed", 0, None)
        self.assertTrue((self.root / "exec-plans/completed/TEST-001.md").exists())
        self.assertTrue(self.store.check_progress())
        self.assertEqual([], self.store.validate())

    def test_progress_detects_manual_edit(self):
        self.store.write_progress()
        self.store.progress_path.write_text("manual\n", encoding="utf-8")
        self.assertFalse(self.store.check_progress())

    def test_completion_without_current_verification_is_rejected(self):
        self.store.start("TEST-001", None)
        with self.assertRaisesRegex(ValueError, "verification"):
            self.store.finish("TEST-001", "completed", 0, None)

    def write_verification(self, evidence, **overrides):
        from harness import git_value
        report = {"schemaVersion": 3, "strictEvidence": True, "invocationId": "test-invocation",
                  "runId": self.store.state()["runId"], "headCommit": git_value(self.root, "rev-parse", "HEAD"),
                  "scopes": ["harness"], "result": "passed", "commandResult": "passed", "testCoverage": "not-assessed",
                  "steps": [{"name": name, "exitCode": 0, "log": str(evidence / (name + ".log")),
                             "logSha256": hashlib.sha256(b"fixture\n").hexdigest()} for name in
                    ("harness-state", "harness-progress", "harness-api-spec", "harness-python-tests", "harness-shell-syntax")]}
        for step in report["steps"]:
            (self.root / step["log"]).write_bytes(b"fixture\n")
        report.update(overrides)
        self.write(str(evidence) + "/verification.json", report)
        return report

    def write_maven_verification(self, evidence):
        tasks = self.store.tasks()
        tasks["tasks"][0].update(scopes=["backend"], verifyScopes=["backend"])
        self.write("harness/tasks.json", tasks)
        counts = {"tests": 1, "executed": 1, "failures": 0, "errors": 0, "skipped": 0, "flakyAttempts": 0}
        tests = {"totals": counts, "classes": [{"class": "example.Test", **counts}], "underfilledSuites": []}
        summary = {"schemaVersion": 1, "result": "passed", "commandExitCode": 0,
                   "step": "backend-tests", "invocationId": "test-invocation", "tests": tests}
        summary_path = evidence / "backend-tests.tests.json"
        self.write(summary_path, summary)
        log = evidence / "backend-tests.log"
        (self.root / log).write_bytes(b"Maven fixture\n")
        step = {"name": "backend-tests", "exitCode": 0, "log": str(log),
                "logSha256": hashlib.sha256(b"Maven fixture\n").hexdigest(), "mavenTests": tests,
                "testSummarySha256": hashlib.sha256((self.root / summary_path).read_bytes()).hexdigest()}
        return self.write_verification(evidence, scopes=["backend"], testCoverage="passed", steps=[step])

    def test_finish_requires_actual_log_bytes_and_current_directory(self):
        evidence = self.store.start("TEST-001", None)
        for kind in ("changed", "missing", "outside", "symlink"):
            with self.subTest(kind=kind):
                report = self.write_verification(evidence)
                log = self.root / report["steps"][0]["log"]
                if kind == "changed":
                    log.write_text("changed")
                elif kind == "missing":
                    log.unlink()
                else:
                    outside = self.root / "outside.log"
                    outside.write_bytes(b"fixture\n")
                    if kind == "outside":
                        report["steps"][0]["log"] = "outside.log"
                        self.write(evidence / "verification.json", report)
                    else:
                        log.unlink()
                        log.symlink_to(outside)
                with self.assertRaises(ValueError):
                    self.store.finish("TEST-001", "completed", 0, None)
                if log.is_symlink():
                    log.unlink()
        self.write_verification(evidence)
        self.store.finish("TEST-001", "completed", 0, None)
        self.assertEqual([], self.store.validate())

    def test_finish_reads_summary_result_identity_counts_and_digest(self):
        evidence = self.store.start("TEST-001", None)
        for change in ("missing", "digest", "partial", "invocation", "step", "counts", "command"):
            with self.subTest(change=change):
                report = self.write_maven_verification(evidence)
                path = self.root / evidence / "backend-tests.tests.json"
                summary = json.loads(path.read_text())
                if change == "missing":
                    path.unlink()
                else:
                    if change in {"digest", "partial"}: summary["result"] = "partial"
                    if change == "invocation": summary["invocationId"] = "another"
                    if change == "step": summary["step"] = "another"
                    if change == "counts": summary["tests"]["totals"]["tests"] = 2
                    if change == "command": summary["commandExitCode"] = 1
                    self.write(evidence / "backend-tests.tests.json", summary)
                    if change != "digest":
                        report["steps"][0]["testSummarySha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
                        self.write(evidence / "verification.json", report)
                with self.assertRaises(ValueError):
                    self.store.finish("TEST-001", "completed", 0, None)
        self.write_maven_verification(evidence)
        self.store.finish("TEST-001", "completed", 0, None)
        self.assertEqual([], self.store.validate())

    def test_completed_cannot_clear_evidence_lower_schema_or_append_fake_legacy(self):
        evidence = self.store.start("TEST-001", None)
        self.write_verification(evidence)
        self.store.finish("TEST-001", "completed", 0, None)
        original = self.store.tasks()
        for change in ("empty", "schema2", "no-schema", "append", "blocked-tail"):
            with self.subTest(change=change):
                tasks = copy.deepcopy(original)
                entries = tasks["tasks"][0]["evidence"]
                if change == "empty": entries.clear()
                if change == "schema2": entries[-1]["verificationSchemaVersion"] = 2
                if change == "no-schema": entries[-1].pop("verificationSchemaVersion")
                if change == "append": entries.append({"runId": "fake", "path": "missing", "result": "completed"})
                if change == "blocked-tail": entries.append({"runId": "fake", "result": "blocked"})
                self.write("harness/tasks.json", tasks)
                self.assertTrue(self.store.validate())
        self.write("harness/tasks.json", original)
        self.assertEqual([], self.store.validate())

    def test_legacy_compatibility_is_an_exact_frozen_prefix_not_a_schema_switch(self):
        tasks = self.store.tasks()
        record = {"runId": "legacy-run", "path": "historical", "result": "completed", "exitCode": 0}
        tasks["tasks"][0].update(status="completed", evidence=[record])
        self.write("harness/tasks.json", tasks)
        digest = hashlib.sha256(json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        self.write("harness/legacy-evidence.json", {"schemaVersion": 1, "tasks": {"TEST-001": [
            {"runId": "legacy-run", "recordSha256": digest}]}})
        self.assertEqual([], self.store.validate())
        for change in ("path", "runId", "clear", "append"):
            with self.subTest(change=change):
                mutated = copy.deepcopy(tasks)
                entries = mutated["tasks"][0]["evidence"]
                if change in {"path", "runId"}: entries[0][change] = "changed"
                elif change == "clear": entries.clear()
                else: entries.append({"runId": "new-fake", "result": "completed"})
                self.write("harness/tasks.json", mutated)
                self.assertTrue(self.store.validate())

    def test_all_completed_records_are_checked_not_only_the_last(self):
        first = self.store.start("TEST-001", None)
        self.write_verification(first)
        self.store.finish("TEST-001", "completed", 0, None)
        tasks = self.store.tasks()
        tasks["tasks"][0]["status"] = "pending"
        self.write("harness/tasks.json", tasks)
        second = self.store.start("TEST-001", None)
        self.write_verification(second)
        self.store.finish("TEST-001", "completed", 0, None)
        report = json.loads((self.root / first / "verification.json").read_text())
        report["result"] = "failed"
        self.write(first / "verification.json", report)
        self.assertTrue(self.store.validate())

    def test_portable_validation_does_not_authorize_finish_without_logs(self):
        evidence = self.store.start("TEST-001", None)
        report = self.write_maven_verification(evidence)
        log = self.root / report["steps"][0]["log"]
        log.unlink()
        with self.assertRaises(ValueError):
            self.store.finish("TEST-001", "completed", 0, None)
        self.assertEqual("running", self.store.tasks()["tasks"][0]["status"])
        self.assertEqual([], self.store.tasks()["tasks"][0]["evidence"])
        report = self.write_maven_verification(evidence)
        self.store.finish("TEST-001", "completed", 0, None)
        self.assertEqual([], self.store.validate())
        log.unlink()
        self.assertEqual([], self.store.validate())  # archive: ignored raw log absent
        log.write_bytes(b"modified log")
        self.assertTrue(self.store.validate())
        log.unlink()
        (self.root / evidence / "backend-tests.tests.json").unlink()
        self.assertTrue(self.store.validate())  # commit-safe summary is mandatory

    def test_summary_mutation_after_finish_is_detected_without_raw_logs(self):
        evidence = self.store.start("TEST-001", None)
        report = self.write_maven_verification(evidence)
        self.store.finish("TEST-001", "completed", 0, None)
        (self.root / report["steps"][0]["log"]).unlink()
        path = self.root / evidence / "backend-tests.tests.json"
        summary = json.loads(path.read_text())
        summary["result"] = "partial"
        self.write(evidence / "backend-tests.tests.json", summary)
        self.assertTrue(self.store.validate())

    def test_missing_inventory_or_malformed_evidence_fails_closed(self):
        inventory = self.root / "harness/legacy-evidence.json"
        inventory.unlink()
        self.assertTrue(self.store.validate())
        self.write("harness/legacy-evidence.json", {"schemaVersion": 1, "tasks": {}})
        original = self.store.tasks()
        for records in (None, {}, [None], [{"runId": "x", "result": "completed"}]):
            with self.subTest(records=records):
                tasks = copy.deepcopy(original)
                tasks["tasks"][0]["evidence"] = records
                self.write("harness/tasks.json", tasks)
                self.assertTrue(self.store.validate())

    def test_summary_and_verification_symlinks_cannot_escape(self):
        evidence = self.store.start("TEST-001", None)
        for name in ("verification.json", "backend-tests.tests.json"):
            with self.subTest(name=name):
                self.write_maven_verification(evidence)
                path = self.root / evidence / name
                outside = self.root / ("outside-" + name)
                outside.write_bytes(path.read_bytes())
                path.unlink()
                path.symlink_to(outside)
                with self.assertRaises(ValueError):
                    self.store.finish("TEST-001", "completed", 0, None)
                self.assertEqual([], self.store.tasks()["tasks"][0]["evidence"])
                path.unlink()

    def test_partial_failed_wrong_run_head_or_scopes_cannot_finish(self):
        evidence = self.store.start("TEST-001", None)
        for override in ({"result": "partial"}, {"result": "failed"}, {"runId": "another"},
                         {"headCommit": "another"}, {"scopes": ["backend"]}, {"steps": []}):
            with self.subTest(override=override):
                self.write_verification(evidence, **override)
                with self.assertRaisesRegex(ValueError, "verification"):
                    self.store.finish("TEST-001", "completed", 0, None)
                self.assertEqual("running", self.store.tasks()["tasks"][0]["status"])

    def test_validate_detects_changed_completed_verification(self):
        evidence = self.store.start("TEST-001", None)
        self.write_verification(evidence)
        self.store.finish("TEST-001", "completed", 0, None)
        self.write_verification(evidence, result="partial")
        self.assertTrue(any("invalid completed verification" in error for error in self.store.validate()))

    def test_high_risk_requires_approval_reference(self):
        tasks = self.store.tasks()
        tasks["tasks"][0].update({"operation": "deployment", "risk": "high_risk"})
        self.write("harness/tasks.json", tasks)
        with self.assertRaisesRegex(ValueError, "requires --approval-ref"):
            self.store.start("TEST-001", None)
        evidence = self.store.start("TEST-001", "message:om_test")
        self.assertTrue((self.root / evidence / "run.json").exists())

    def test_validation_rejects_schema_drift(self):
        tasks = self.store.tasks()
        tasks["unexpected"] = True
        tasks["tasks"][0]["typoField"] = "must fail"
        self.write("harness/tasks.json", tasks)
        errors = self.store.validate()
        self.assertTrue(any("tasks.json has unknown fields" in error for error in errors))
        self.assertTrue(any("typoField" in error for error in errors))

    def test_run_task_shell_completes_and_records_evidence(self):
        self.assert_run_task_shell("passed", 0, "completed")

    def test_run_task_partial_is_blocked_and_reason_preserves_partial(self):
        self.assert_run_task_shell("partial", 1, "blocked")

    def test_run_task_cannot_complete_with_a_passed_manifest_but_missing_log(self):
        self.assert_run_task_shell("passed", 2, "blocked", remove_log=True)

    def assert_run_task_shell(self, verification_result, verification_exit, outcome, remove_log=False):
        source_harness = Path(__file__).resolve().parents[1]
        shutil.copy2(source_harness / "harness.py", self.root / "harness/harness.py")
        shutil.copy2(source_harness / "run-task.sh", self.root / "harness/run-task.sh")
        verify = self.root / "harness/verify.sh"
        verify.write_text(
            "#!/bin/sh\n"
            "set -eu\n"
            "while [ \"$#\" -gt 0 ]; do\n"
            "  case \"$1\" in\n"
            "    --evidence-dir) evidence=$2; shift 2 ;;\n"
            "    *) shift ;;\n"
            "  esac\n"
            "done\n"
            "mkdir -p \"$evidence\"\n"
            "python3 - \"$evidence\" <<'PY'\n"
            "import json,sys,subprocess,hashlib\nfrom pathlib import Path\n"
            "p=Path(sys.argv[1]); run=json.loads((p/'run.json').read_text())\n"
            "report={'schemaVersion':3,'strictEvidence':True,'invocationId':'test', 'runId':run['runId'],"
            "'headCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),"
            "'scopes':['harness'],'result':'passed','commandResult':'passed','testCoverage':'not-assessed',"
            "'steps':[{'name':n,'exitCode':0,'log':str(p/(n+'.log')),'logSha256':hashlib.sha256(b'fixture').hexdigest()} for n in "
            "['harness-state','harness-progress','harness-api-spec','harness-python-tests','harness-shell-syntax']]}\n"
            "for step in report['steps']: Path(step['log']).write_bytes(b'fixture')\n"
            "report['result']=" + repr(verification_result) + "\n"
            "(p/'verification.json').write_text(json.dumps(report))\n"
            + ("Path(report['steps'][0]['log']).unlink()\n" if remove_log else "")
            + "PY\nexit " + str(0 if remove_log else verification_exit) + "\n",
            encoding="utf-8",
        )
        verify.chmod(0o755)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.name", "Harness Test"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.email", "harness@test.invalid"], cwd=self.root, check=True)
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-qm", "test baseline"], cwd=self.root, check=True)

        result = subprocess.run(
            [str(self.root / "harness/run-task.sh"), "TEST-001", "--", "sh", "-c", "true"],
            cwd=self.root,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(verification_exit, result.returncode, result.stderr)
        task = self.store.tasks()["tasks"][0]
        self.assertEqual(outcome, task["status"])
        if verification_result == "partial":
            self.assertEqual("verification partial", task["blockedReason"])
        self.assertEqual(1, len(task["evidence"]))
        evidence = self.root / task["evidence"][0]["path"]
        self.assertTrue((evidence / "run.json").exists())
        self.assertTrue((evidence / "verification.json").exists())


if __name__ == "__main__":
    unittest.main()
