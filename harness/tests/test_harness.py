import json
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
        self.store.finish("TEST-001", "completed", 0, None)
        self.assertTrue((self.root / "exec-plans/completed/TEST-001.md").exists())
        self.assertTrue(self.store.check_progress())
        self.assertEqual([], self.store.validate())

    def test_progress_detects_manual_edit(self):
        self.store.write_progress()
        self.store.progress_path.write_text("manual\n", encoding="utf-8")
        self.assertFalse(self.store.check_progress())

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
            "printf '{\"result\":\"passed\"}\\n' >\"$evidence/verification.json\"\n",
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
        self.assertEqual(0, result.returncode, result.stderr)
        task = self.store.tasks()["tasks"][0]
        self.assertEqual("completed", task["status"])
        self.assertEqual(1, len(task["evidence"]))
        evidence = self.root / task["evidence"][0]["path"]
        self.assertTrue((evidence / "run.json").exists())
        self.assertTrue((evidence / "verification.json").exists())


if __name__ == "__main__":
    unittest.main()
