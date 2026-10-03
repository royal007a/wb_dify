#!/usr/bin/env python3
"""Run Maven into a new private report directory and enforce the suite inventory."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from verification_report import summarize_xml


def run(root, evidence, step, invocation):
    inventory_path = root / "harness/expected-maven-suites.json"
    inventory_bytes = inventory_path.read_bytes()
    expected = json.loads(inventory_bytes)[step]
    # No cleanup of target or shared reports. A brand new directory excludes stale XML.
    reports = Path(tempfile.mkdtemp(prefix=step + "-", dir=evidence)) / "reports"
    reports.mkdir()
    result = {"schemaVersion": 1, "step": step, "invocationId": invocation,
              "inventorySha256": hashlib.sha256(inventory_bytes).hexdigest(),
              "expectedSuites": expected, "reportDirectory": str(reports.relative_to(evidence)),
              "result": "failed", "tests": {"totals": {"tests": 0, "executed": 0,
              "failures": 0, "errors": 0, "skipped": 0, "flakyAttempts": 0}, "classes": []}}
    try:
        if step == "backend-tests":
            # Full scope must not silently omit a newly added/deleted test class.
            sources = set()
            for path in (root / "backend").glob("*/src/test/java/**/*.java"):
                name = path.stem
                if (name.startswith("Test") or name.endswith(("Test", "Tests", "TestCase"))):
                    relative = path.as_posix().split("/src/test/java/", 1)[1][:-5]
                    qualified = relative.replace("/", ".")
                    if qualified in sources:
                        raise ValueError("duplicate qualified test class across modules")
                    sources.add(qualified)
            if sources != set(expected):
                raise ValueError("backend source inventory changed; update expected-maven-suites.json in review")
        project = "hify-chat" if step == "intent-context-recall-eval" else "hify-app"
        command = ["mvn", "-B", "-Dapi.version=" + os.environ.get("HIFY_DOCKER_API_VERSION", "1.44"),
                   "-pl", project, "-am", "-Dtest=" + ",".join(sorted(expected)),
                   "-Dsurefire.failIfNoSpecifiedTests=false", "-Dhify.test.reportsDirectory=" + str(reports), "test"]
        result["command"] = command
        code = subprocess.run(command, cwd=root / "backend", check=False).returncode
        result["commandExitCode"] = code
        result["tests"] = summarize_xml(reports, expected)
        totals = result["tests"]["totals"]
        if code or totals["failures"] or totals["errors"] or totals["flakyAttempts"] or result["tests"]["underfilledSuites"]:
            result["result"] = "failed"
        elif totals["skipped"]:
            result["result"] = "partial"
        else:
            result["result"] = "passed"
    except (OSError, ValueError, KeyError, ET.ParseError) as exc:
        result["summaryError"] = type(exc).__name__ + ": missing, inconsistent or incomplete invocation evidence"
    (evidence / (step + ".tests.json")).write_text(json.dumps(result, indent=2) + "\n")
    print("Maven evidence: " + result["result"], flush=True)
    # partial is translated into a failing gate by verification_report; retain command outcome.
    return 1 if result["result"] == "failed" else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--step", required=True)
    parser.add_argument("--invocation-id", required=True)
    args = parser.parse_args()
    raise SystemExit(run(args.root, args.evidence_dir, args.step, args.invocation_id))
