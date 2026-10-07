#!/usr/bin/env python3
"""Capture synthetic legacy goldens from a fixed archive; retain every attempt, never overwrite."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

BASELINE = "05ab7a06f485a5d800009dedbbe566508bd9e775"
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def main():
    attempt = Path(tempfile.mkdtemp(prefix="hify-authority-golden-"))
    tree = attempt / "tree"
    tree.mkdir()
    output = attempt / "goldens"
    output.mkdir()
    reports = attempt / "reports"
    reports.mkdir()
    archive = attempt / "baseline.tar"
    manifest = {"baseline": BASELINE, "startedAt": now(), "result": "failed",
                "collectorSha256": sha(HERE / "LegacyContextGoldenCaptureTest.java"),
                "driverSha256": sha(Path(__file__)), "scope": "Synthetic old-writer H2 capture, not product acceptance"}
    print("captureAttempt=" + str(attempt), flush=True)
    try:
        with archive.open("xb") as handle:
            subprocess.run(["git", "archive", BASELINE, "backend"], cwd=ROOT, stdout=handle, check=True)
        manifest["archiveSha256"] = sha(archive)
        with tarfile.open(archive) as source:
            # git archive is trusted, but never allow a path or link outside this new scratch.
            source.extractall(tree, filter="data")
        originals = {str(p.relative_to(tree)): sha(p) for p in sorted(tree.rglob("*")) if p.is_file()}
        (attempt / "source-sha256.json").write_text(json.dumps(originals, indent=2) + "\n")
        destination = tree / "backend/hify-app/src/test/java/com/hify/api/LegacyContextGoldenCaptureTest.java"
        if destination.exists():
            raise RuntimeError("collector must not replace a baseline file")
        shutil.copyfile(HERE / destination.name, destination)
        command = ["mvn", "-B", "-pl", "hify-app", "-am",
                   "-Dtest=LegacyContextGoldenCaptureTest", "-Dsurefire.failIfNoSpecifiedTests=false",
                   "-Dhify.test.reportsDirectory=" + str(reports),
                   "-Dhify.golden.output=" + str(output), "test"]
        manifest["command"] = command
        manifest["cwd"] = str(tree / "backend")
        env = dict(os.environ)
        env["MAVEN_OPTS"] = "-Xmx768m"
        env["JAVA_TOOL_OPTIONS"] = ("-Xmx512m -Dhttp.nonProxyHosts=localhost|127.*|[::1] "
                                    "-Dhttps.nonProxyHosts=localhost|127.*|[::1] "
                                    "-DsocksNonProxyHosts=localhost|127.*|[::1]")
        with (attempt / "maven.log").open("xb") as log:
            manifest["exitCode"] = subprocess.run(command, cwd=tree / "backend", env=env,
                                                   stdout=log, stderr=subprocess.STDOUT, check=False).returncode
        manifest["baselineFilesUnchanged"] = all((tree / name).is_file() and sha(tree / name) == digest
                                                  for name, digest in originals.items())
        summaries = []
        for path in sorted(reports.rglob("TEST-*.xml")):
            suite = ET.parse(path).getroot()
            summaries.append({"name": suite.attrib["name"], "tests": int(suite.attrib["tests"]),
                              "failures": int(suite.attrib["failures"]), "errors": int(suite.attrib["errors"]),
                              "skipped": int(suite.attrib.get("skipped", "0")), "xmlSha256": sha(path)})
        manifest["suites"] = summaries
        expected = len(summaries) == 1 and summaries[0]["name"] == "com.hify.api.LegacyContextGoldenCaptureTest"
        passed = expected and summaries[0]["tests"] == 1 and all(summaries[0][k] == 0 for k in ("failures", "errors", "skipped"))
        manifest["goldenSha256"] = {str(p.relative_to(output)): sha(p) for p in sorted(output.rglob("*")) if p.is_file()}
        if manifest["exitCode"] == 0 and passed and manifest["baselineFilesUnchanged"]:
            manifest["result"] = "passed"
    except Exception as failure:
        # Avoid including environment, connection details or exception text in publishable metadata.
        manifest["failureType"] = type(failure).__name__
        raise
    finally:
        manifest["finishedAt"] = now()
        if (attempt / "maven.log").exists():
            manifest["logSha256"] = sha(attempt / "maven.log")
        (attempt / "capture.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print("captureResult=" + manifest["result"], flush=True)
    return 0 if manifest["result"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
