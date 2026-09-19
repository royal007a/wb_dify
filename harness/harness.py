#!/usr/bin/env python3
"""Machine-state controller for Hify's engineering harness."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ALLOWED_STATUSES = {"pending", "running", "completed", "blocked"}
ALLOWED_PRIORITIES = {"P0", "P1", "P2"}
ALLOWED_SCOPES = {"backend", "frontend", "migration", "runtime", "eval", "harness"}
ALLOWED_RISKS = {"read_only", "reversible_write", "high_risk", "prohibited"}
TASKS_DOCUMENT_FIELDS = {"schemaVersion", "project", "updatedAt", "tasks"}
TASK_FIELDS = {
    "id", "title", "description", "priority", "status", "scopes", "verifyScopes",
    "risk", "operation", "approvalRef", "dependsOn", "acceptance", "planPath",
    "checkpoint", "blockedReason", "evidence", "createdAt", "updatedAt",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def git_value(root: Path, *args: str, fallback: str = "unknown") -> str:
    result = subprocess.run(
        ["git", *args], cwd=root, text=True, capture_output=True, check=False
    )
    return result.stdout.strip() if result.returncode == 0 and result.stdout.strip() else fallback


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


@dataclass
class HarnessStore:
    root: Path

    @property
    def harness_dir(self) -> Path:
        return self.root / "harness"

    @property
    def tasks_path(self) -> Path:
        return self.harness_dir / "tasks.json"

    @property
    def state_path(self) -> Path:
        return self.harness_dir / "state.json"

    @property
    def permissions_path(self) -> Path:
        return self.harness_dir / "permissions.yaml"

    @property
    def progress_path(self) -> Path:
        return self.harness_dir / "progress.md"

    def read_json(self, path: Path) -> Any:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError as exc:
            raise ValueError(f"missing required file: {path.relative_to(self.root)}") from exc
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON in {path.relative_to(self.root)}: {exc}") from exc

    def tasks(self) -> dict[str, Any]:
        return self.read_json(self.tasks_path)

    def state(self) -> dict[str, Any]:
        return self.read_json(self.state_path)

    def permissions(self) -> dict[str, Any]:
        # JSON is valid YAML 1.2 and keeps the runtime dependency-free.
        return self.read_json(self.permissions_path)

    def task(self, tasks: dict[str, Any], task_id: str) -> dict[str, Any]:
        for task in tasks["tasks"]:
            if task["id"] == task_id:
                return task
        raise ValueError(f"unknown task: {task_id}")

    def validate(self) -> list[str]:
        errors: list[str] = []
        tasks_doc = self.tasks()
        state = self.state()
        permissions = self.permissions()

        if tasks_doc.get("schemaVersion") != 1:
            errors.append("tasks.json schemaVersion must be 1")
        unknown_document_fields = set(tasks_doc) - TASKS_DOCUMENT_FIELDS
        if unknown_document_fields:
            errors.append(f"tasks.json has unknown fields: {', '.join(sorted(unknown_document_fields))}")
        if tasks_doc.get("project") != "hify":
            errors.append("tasks.json project must be hify")
        if state.get("schemaVersion") != 1:
            errors.append("state.json schemaVersion must be 1")
        if permissions.get("schemaVersion") != 1:
            errors.append("permissions.yaml schemaVersion must be 1")

        tasks = tasks_doc.get("tasks")
        if not isinstance(tasks, list):
            return errors + ["tasks.json tasks must be an array"]

        ids: list[str] = []
        for index, task in enumerate(tasks):
            prefix = f"tasks[{index}]"
            unknown_task_fields = set(task) - TASK_FIELDS
            missing_task_fields = TASK_FIELDS - set(task)
            if unknown_task_fields:
                errors.append(f"{prefix} has unknown fields: {', '.join(sorted(unknown_task_fields))}")
            if missing_task_fields:
                errors.append(f"{prefix} is missing fields: {', '.join(sorted(missing_task_fields))}")
            task_id = task.get("id")
            if not isinstance(task_id, str) or not task_id:
                errors.append(f"{prefix}.id must be non-empty")
                continue
            ids.append(task_id)
            if task.get("status") not in ALLOWED_STATUSES:
                errors.append(f"{task_id}: invalid status")
            if task.get("priority") not in ALLOWED_PRIORITIES:
                errors.append(f"{task_id}: invalid priority")
            scopes = task.get("scopes")
            if not isinstance(scopes, list) or not scopes or not set(scopes) <= ALLOWED_SCOPES:
                errors.append(f"{task_id}: scopes must be a non-empty supported set")
            verify_scopes = task.get("verifyScopes")
            if not isinstance(verify_scopes, list) or not verify_scopes or not set(verify_scopes) <= ALLOWED_SCOPES:
                errors.append(f"{task_id}: verifyScopes must be a non-empty supported set")
            if task.get("risk") not in ALLOWED_RISKS:
                errors.append(f"{task_id}: invalid risk")
            operation = task.get("operation")
            expected_risk = permissions.get("operations", {}).get(operation)
            if expected_risk is None:
                errors.append(f"{task_id}: operation is not declared in permissions.yaml")
            elif expected_risk != task.get("risk"):
                errors.append(f"{task_id}: risk must match operation policy ({expected_risk})")
            if not isinstance(task.get("acceptance"), list) or not task["acceptance"]:
                errors.append(f"{task_id}: acceptance must not be empty")
            if not isinstance(task.get("dependsOn"), list):
                errors.append(f"{task_id}: dependsOn must be an array")
            for field in ("title", "description", "createdAt", "updatedAt"):
                if not isinstance(task.get(field), str) or not task[field]:
                    errors.append(f"{task_id}: {field} must be non-empty")
            plan_path = task.get("planPath")
            if plan_path is not None:
                if not isinstance(plan_path, str) or not plan_path.startswith("exec-plans/"):
                    errors.append(f"{task_id}: planPath must be under exec-plans/")
                elif not (self.root / plan_path).exists():
                    errors.append(f"{task_id}: planPath does not exist: {plan_path}")

        if len(ids) != len(set(ids)):
            errors.append("task ids must be unique")
        known = set(ids)
        for task in tasks:
            for dependency in task.get("dependsOn", []):
                if dependency not in known:
                    errors.append(f"{task.get('id')}: unknown dependency {dependency}")
                if dependency == task.get("id"):
                    errors.append(f"{task.get('id')}: task cannot depend on itself")

        graph = {task["id"]: task.get("dependsOn", []) for task in tasks if task.get("id")}
        visiting: set[str] = set()
        visited: set[str] = set()

        def visit(node: str) -> None:
            if node in visiting:
                errors.append(f"dependency cycle contains {node}")
                return
            if node in visited:
                return
            visiting.add(node)
            for child in graph.get(node, []):
                if child in graph:
                    visit(child)
            visiting.remove(node)
            visited.add(node)

        for task_id in graph:
            visit(task_id)

        running = [task["id"] for task in tasks if task.get("status") == "running"]
        current = state.get("currentTaskId")
        if len(running) > 1:
            errors.append("at most one task may be running")
        if running != ([current] if current else []):
            errors.append("state.currentTaskId must exactly match the running task")
        if current and not state.get("runId"):
            errors.append("a running task requires state.runId")
        if not current and any(state.get(field) is not None for field in ("runId", "baselineCommit", "startedAt", "evidencePath")):
            errors.append("idle state must clear runId/baselineCommit/startedAt/evidencePath")
        return errors

    def render_progress(self) -> str:
        tasks_doc = self.tasks()
        state = self.state()
        tasks = tasks_doc["tasks"]
        counts = {status: sum(task["status"] == status for task in tasks) for status in ALLOWED_STATUSES}
        lines = [
            "<!-- GENERATED by harness/harness.py; DO NOT EDIT. -->",
            "# Hify Harness Progress",
            "",
            f"- State source: `harness/tasks.json`",
            f"- Generated from task state updated at: `{tasks_doc['updatedAt']}`",
            f"- Current task: `{state.get('currentTaskId') or 'none'}`",
            f"- Counts: pending {counts['pending']} · running {counts['running']} · blocked {counts['blocked']} · completed {counts['completed']}",
            "",
            "| ID | Priority | Status | Scope | Risk | Title |",
            "|---|---|---|---|---|---|",
        ]
        for task in sorted(tasks, key=lambda item: (item["priority"], item["id"])):
            lines.append(
                f"| `{task['id']}` | {task['priority']} | {task['status']} | "
                f"{', '.join(task['scopes'])} | {task['risk']} | {task['title']} |"
            )
        blocked = [task for task in tasks if task["status"] == "blocked"]
        if blocked:
            lines.extend(["", "## Blocked"])
            for task in blocked:
                lines.append(f"- `{task['id']}`: {task.get('blockedReason') or 'reason not recorded'}")
        lines.extend([
            "",
            "Regenerate with `python3 harness/harness.py render-progress`; verify with `python3 harness/harness.py check-progress`.",
            "",
        ])
        return "\n".join(lines)

    def write_progress(self) -> None:
        self.progress_path.write_text(self.render_progress(), encoding="utf-8")

    def check_progress(self) -> bool:
        try:
            actual = self.progress_path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return False
        return actual == self.render_progress()

    def start(self, task_id: str, approval_ref: str | None) -> Path:
        errors = self.validate()
        if errors:
            raise ValueError("invalid harness state: " + "; ".join(errors))
        tasks_doc = self.tasks()
        state = self.state()
        if state["currentTaskId"] is not None:
            raise ValueError(f"task {state['currentTaskId']} is already running")
        task = self.task(tasks_doc, task_id)
        if task["status"] not in {"pending", "blocked"}:
            raise ValueError(f"task {task_id} cannot start from {task['status']}")
        statuses = {item["id"]: item["status"] for item in tasks_doc["tasks"]}
        incomplete = [item for item in task["dependsOn"] if statuses.get(item) != "completed"]
        if incomplete:
            raise ValueError(f"task {task_id} has incomplete dependencies: {', '.join(incomplete)}")

        policy = self.permissions()["riskLevels"][task["risk"]]
        if not policy["allowed"]:
            raise ValueError(f"task {task_id} is prohibited by permissions.yaml")
        if policy["requiresApproval"] and not approval_ref:
            raise ValueError(f"task {task_id} requires --approval-ref")

        now = utc_now()
        run_id = f"{task_id}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
        evidence_path = Path("harness/evidence") / task_id / run_id
        absolute_evidence = self.root / evidence_path
        absolute_evidence.mkdir(parents=True, exist_ok=False)
        baseline = git_value(self.root, "rev-parse", "HEAD")
        task.update({
            "status": "running",
            "approvalRef": approval_ref,
            "blockedReason": None,
            "checkpoint": None,
            "updatedAt": now,
        })
        tasks_doc["updatedAt"] = now
        state.update({
            "currentTaskId": task_id,
            "runId": run_id,
            "baselineCommit": baseline,
            "startedAt": now,
            "evidencePath": evidence_path.as_posix(),
            "lastCheckpoint": None,
            "updatedAt": now,
        })
        atomic_json(self.tasks_path, tasks_doc)
        atomic_json(self.state_path, state)
        atomic_json(absolute_evidence / "run.json", {
            "schemaVersion": 1,
            "runId": run_id,
            "taskId": task_id,
            "status": "running",
            "baselineCommit": baseline,
            "approvalRef": approval_ref,
            "startedAt": now,
        })
        self.write_progress()
        return evidence_path

    def checkpoint(self, task_id: str, summary: str) -> None:
        tasks_doc = self.tasks()
        state = self.state()
        if state.get("currentTaskId") != task_id:
            raise ValueError(f"task {task_id} is not the current running task")
        now = utc_now()
        checkpoint = {
            "summary": summary,
            "headCommit": git_value(self.root, "rev-parse", "HEAD"),
            "workingTreeHash": self.working_tree_hash(),
            "evidencePath": state["evidencePath"],
            "updatedAt": now,
        }
        self.task(tasks_doc, task_id)["checkpoint"] = checkpoint
        self.task(tasks_doc, task_id)["updatedAt"] = now
        tasks_doc["updatedAt"] = now
        state["lastCheckpoint"] = checkpoint
        state["updatedAt"] = now
        atomic_json(self.tasks_path, tasks_doc)
        atomic_json(self.state_path, state)
        atomic_json(self.root / state["evidencePath"] / "checkpoint.json", checkpoint)
        self.write_progress()

    def working_tree_hash(self) -> str:
        digest = hashlib.sha256()
        diff = subprocess.run(
            ["git", "diff", "--binary", "HEAD"],
            cwd=self.root,
            capture_output=True,
            check=False,
        )
        digest.update(diff.stdout)
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "-z"],
            cwd=self.root,
            capture_output=True,
            check=False,
        )
        for relative_bytes in sorted(filter(None, untracked.stdout.split(b"\0"))):
            relative = relative_bytes.decode("utf-8", errors="surrogateescape")
            path = self.root / relative
            digest.update(relative_bytes)
            digest.update(b"\0")
            if path.is_file():
                digest.update(hashlib.sha256(path.read_bytes()).digest())
        return digest.hexdigest()

    def finish(self, task_id: str, outcome: str, exit_code: int, reason: str | None) -> None:
        if outcome not in {"completed", "blocked"}:
            raise ValueError("outcome must be completed or blocked")
        tasks_doc = self.tasks()
        state = self.state()
        if state.get("currentTaskId") != task_id:
            raise ValueError(f"task {task_id} is not the current running task")
        task = self.task(tasks_doc, task_id)
        now = utc_now()
        evidence_path = state["evidencePath"]
        head = git_value(self.root, "rev-parse", "HEAD")
        record = {
            "runId": state["runId"],
            "path": evidence_path,
            "result": outcome,
            "exitCode": exit_code,
            "baselineCommit": state["baselineCommit"],
            "headCommit": head,
            "recordedAt": now,
        }
        task.setdefault("evidence", []).append(record)
        task["status"] = outcome
        task["blockedReason"] = reason if outcome == "blocked" else None
        task["updatedAt"] = now
        task["checkpoint"] = None
        self.move_plan(task, outcome)
        tasks_doc["updatedAt"] = now
        state.update({
            "currentTaskId": None,
            "runId": None,
            "baselineCommit": None,
            "startedAt": None,
            "evidencePath": None,
            "lastCheckpoint": None,
            "lastVerification": record,
            "lastCompletedTaskId": task_id if outcome == "completed" else state.get("lastCompletedTaskId"),
            "updatedAt": now,
        })
        atomic_json(self.tasks_path, tasks_doc)
        atomic_json(self.state_path, state)
        run_path = self.root / evidence_path / "run.json"
        run = self.read_json(run_path)
        run.update({"status": outcome, "exitCode": exit_code, "reason": reason, "headCommit": head, "finishedAt": now})
        atomic_json(run_path, run)
        self.write_progress()

    def move_plan(self, task: dict[str, Any], outcome: str) -> None:
        plan_path = task.get("planPath")
        if not plan_path:
            return
        source = self.root / plan_path
        if not source.exists() or source.parent.name != "active":
            return
        destination = self.root / "exec-plans" / ("completed" if outcome == "completed" else "blocked") / source.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(source, destination)
        task["planPath"] = destination.relative_to(self.root).as_posix()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate")
    commands.add_parser("render-progress")
    commands.add_parser("check-progress")
    scopes = commands.add_parser("verify-scopes")
    scopes.add_argument("task_id")
    start = commands.add_parser("start")
    start.add_argument("task_id")
    start.add_argument("--approval-ref")
    checkpoint = commands.add_parser("checkpoint")
    checkpoint.add_argument("task_id")
    checkpoint.add_argument("summary")
    finish = commands.add_parser("finish")
    finish.add_argument("task_id")
    finish.add_argument("outcome", choices=["completed", "blocked"])
    finish.add_argument("--exit-code", type=int, required=True)
    finish.add_argument("--reason")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    store = HarnessStore(args.root.resolve())
    try:
        if args.command == "validate":
            errors = store.validate()
            if errors:
                for error in errors:
                    print(f"ERROR: {error}", file=sys.stderr)
                return 1
            print("Harness state is valid")
        elif args.command == "render-progress":
            store.write_progress()
            print(store.progress_path.relative_to(store.root))
        elif args.command == "check-progress":
            if not store.check_progress():
                print("ERROR: harness/progress.md is stale or manually edited", file=sys.stderr)
                return 1
            print("Generated progress is current")
        elif args.command == "verify-scopes":
            print(",".join(store.task(store.tasks(), args.task_id)["verifyScopes"]))
        elif args.command == "start":
            print(store.start(args.task_id, args.approval_ref))
        elif args.command == "checkpoint":
            store.checkpoint(args.task_id, args.summary)
            print("checkpoint saved")
        elif args.command == "finish":
            store.finish(args.task_id, args.outcome, args.exit_code, args.reason)
            print(args.outcome)
        return 0
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
