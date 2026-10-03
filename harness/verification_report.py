#!/usr/bin/env python3
"""Commit-safe per-class counts from this invocation's logs, never stale target XML.

The CLI can enrich a historical manifest into a *different* output file. It does
not claim to rerun tests. Counts across steps are intentionally not added because
backend/runtime/migration may execute the same test more than once.
"""
import argparse
import copy
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path


MAVEN_STEPS = {"backend-tests", "runtime-tests", "migration-postgres", "intent-context-recall-eval", "api-inventory"}
ANSI = re.compile(r"\x1b\[[0-9;]*m")
CLASS = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), (?:Flakes: \d+, )?Time elapsed: [^\n]*? -- in (\S+)")


def summarize_maven(text):
    classes = []
    seen = set()
    text = ANSI.sub("", text)
    if re.search(r"Flakes: [1-9][0-9]*", text):
        raise ValueError("flaky reruns are not a clean pass")
    for line in text.splitlines():
        if "Tests run:" not in line or "-- in " not in line:
            continue
        match = CLASS.search(line)
        if not match:
            raise ValueError("unrecognized per-class summary")
        tests, failures, errors, skipped = map(int, match.groups()[:4])
        name = match[5]
        if name in seen or failures + errors + skipped > tests:
            raise ValueError("duplicate class or inconsistent counts")
        seen.add(name)
        classes.append({"class": name, "tests": tests, "executed": tests - skipped,
                        "failures": failures, "errors": errors, "skipped": skipped})
    if not classes or not sum(row["tests"] for row in classes):
        raise ValueError("no per-class test results in this invocation")
    totals = {key: sum(row[key] for row in classes)
              for key in ("tests", "executed", "failures", "errors", "skipped")}
    return {"totals": totals, "classes": classes}


def summarize_xml(directory, expected):
    """Fresh invocation only; do not read target/ or use test stdout as counts."""
    classes, seen = [], set()
    if not expected:
        raise ValueError("empty expected suite inventory")
    for path in sorted(directory.glob("TEST-*.xml")):
        raw = path.read_bytes()
        suite = ET.fromstring(raw)
        name = suite.get("name")
        if suite.tag != "testsuite" or name not in expected or name in seen:
            raise ValueError("unexpected or duplicate suite")
        seen.add(name)
        cases = suite.findall("testcase")
        counts = {"tests": len(cases), "failures": 0, "errors": 0, "skipped": 0}
        flaky = 0
        for case in cases:
            outcomes = [kind for kind in ("failure", "error", "skipped") if case.find(kind) is not None]
            if len(outcomes) > 1:
                raise ValueError("contradictory testcase outcomes")
            for kind in outcomes:
                counts[{"failure": "failures", "error": "errors", "skipped": "skipped"}[kind]] += 1
            flaky += sum(len(case.findall(kind)) for kind in
                         ("flakyFailure", "flakyError", "rerunFailure", "rerunError"))
        if any(int(suite.get(key, "-1")) != value for key, value in counts.items()):
            raise ValueError("XML suite and testcase counts disagree")
        counts["executed"] = counts["tests"] - counts["skipped"]
        classes.append({"class": name, **counts, "flakyAttempts": flaky,
                        "expectedMinimum": expected[name], "xmlSha256": hashlib.sha256(raw).hexdigest()})
    missing = sorted(set(expected) - seen)
    if missing:
        raise ValueError("missing expected suites: " + ", ".join(missing))
    totals = {key: sum(row[key] for row in classes)
              for key in ("tests", "executed", "failures", "errors", "skipped", "flakyAttempts")}
    # A skipped container may report only one testcase for many intended methods.
    # Preserve that as partial, never as a precise count of unexecuted methods.
    underfilled = [row["class"] for row in classes
                   if not row["skipped"] and row["tests"] < row["expectedMinimum"]]
    return {"totals": totals, "classes": classes, "underfilledSuites": underfilled,
            "countMeaning": "Reported testcase/skip records; disabled containers can hide additional intended methods."}


def enrich(manifest, root):
    report = copy.deepcopy(manifest)
    report["schemaVersion"] = 3 if report.get("strictEvidence") else 2
    report["commandResult"] = "passed" if report.get("steps") and all(
        step["exitCode"] == 0 for step in report["steps"]) else "failed"
    failed = report["commandResult"] != "passed"
    skipped = assessed = False
    for step in report.get("steps", []):
        path = Path(step["log"])
        if not path.is_absolute():
            path = root / path
        try:
            raw = path.read_bytes()
            step["logSha256"] = hashlib.sha256(raw).hexdigest()
            if step["name"] in MAVEN_STEPS:
                assessed = True
                if report.get("strictEvidence"):
                    summary_path = path.parent / (step["name"] + ".tests.json")
                    summary = json.loads(summary_path.read_text())
                    if summary.get("invocationId") != report["invocationId"] or summary.get("step") != step["name"]:
                        raise ValueError("wrong invocation or step")
                    step["mavenTests"] = summary["tests"]
                    step["testSummarySha256"] = hashlib.sha256(summary_path.read_bytes()).hexdigest()
                    failed |= summary.get("result") == "failed"
                    skipped |= summary.get("result") == "partial"
                else:
                    step["mavenTests"] = summarize_maven(raw.decode("utf-8", errors="replace"))
                totals = step["mavenTests"]["totals"]
                failed |= bool(totals["failures"] or totals["errors"] or totals.get("flakyAttempts", 0))
                skipped |= bool(totals["skipped"])
        except (OSError, ValueError, KeyError, TypeError) as exc:
            # No raw exception/path payload: summaries must be safe to commit.
            step["summaryError"] = type(exc).__name__ + ": missing or invalid invocation evidence"
            failed = True
    report["testCoverage"] = "failed" if failed else "partial" if skipped else "passed" if assessed else "not-assessed"
    report["result"] = "failed" if failed else "partial" if skipped else "passed"
    report["resultMeaning"] = "Selected commands and observed test counts only; not full product or deployment acceptance. Repeated steps are not independent tests."
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--verification", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.verification.resolve() == args.output.resolve():
        parser.error("output must differ from original verification evidence")
    original = args.verification.read_bytes()
    report = enrich(json.loads(original), args.root)
    report["sourceManifestSha256"] = hashlib.sha256(original).hexdigest()
    report["sourceManifest"] = str(args.verification)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if report["result"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
