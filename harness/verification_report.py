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
from pathlib import Path


MAVEN_STEPS = {"backend-tests", "runtime-tests", "migration-postgres", "intent-context-recall-eval", "api-inventory"}
ANSI = re.compile(r"\x1b\[[0-9;]*m")
CLASS = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: [^\n]*? -- in (\S+)")


def summarize_maven(text):
    classes = []
    seen = set()
    for match in CLASS.finditer(ANSI.sub("", text)):
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


def enrich(manifest, root):
    report = copy.deepcopy(manifest)
    report["schemaVersion"] = 2
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
                step["mavenTests"] = summarize_maven(raw.decode("utf-8", errors="replace"))
                totals = step["mavenTests"]["totals"]
                failed |= bool(totals["failures"] or totals["errors"])
                skipped |= bool(totals["skipped"])
        except (OSError, ValueError) as exc:
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
