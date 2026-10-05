#!/usr/bin/env python3
"""Fixed samples against local Ollama, through an owned loopback Hify/H2 instance.

All six attempts are retained, including format failures. This is neither a quality
benchmark nor a claim that prompt-only structured output always succeeds.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent


def main():
    state = json.loads((ROOT / "harness/state.json").read_text())
    if state.get("currentTaskId") != "WORKFLOW-STRUCTURED-001":
        raise SystemExit("WORKFLOW-STRUCTURED-001 must be the active atomic task")
    evidence = ROOT / state["evidencePath"]
    if subprocess.check_output(["git", "diff", "HEAD", "--", "backend", "frontend"], cwd=ROOT):
        raise SystemExit("Product source must match committed HEAD")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 28083))
    subprocess.run(["mvn", "-B", "-o", "-pl", "hify-app", "-am", "-DskipTests", "package"],
                   cwd=ROOT / "backend", check=True)
    env = dict(os.environ)
    env.update(HIFY_DB_URL="jdbc:h2:mem:structured_live;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
               HIFY_DB_USERNAME="sa", HIFY_DB_PASSWORD="", HIFY_PORT="28083",
               HIFY_PROVIDER_ALLOW_PRIVATE="true", STRUCTURED_LIVE_KEY="synthetic-local-ollama",
               HIFY_CREDENTIAL_REFERENCE_BINDINGS=json.dumps({
                   "env:STRUCTURED_LIVE_KEY": ["http://127.0.0.1:11434/v1"]}))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(method, path, body=None):
        req = urllib.request.Request("http://127.0.0.1:28083/api/v1" + path, method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json"})
        with opener.open(req, timeout=75) as response:
            return json.load(response)["data"]

    report = {
        "result": "running", "realModel": "qwen2.5:0.5b", "sampleCount": 6,
        "schemaRepairRetries": 0, "samples": [],
        "headCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "backendTree": subprocess.check_output(["git", "rev-parse", "HEAD:backend"], cwd=ROOT, text=True).strip(),
        "sourceDiffEmpty": True,
        "scope": "Six predetermined synthetic local-model samples; not a quality benchmark, native constrained decoding, production deployment, or proof of zero transport retries",
        "acceptance": "All six attempts recorded; at least one schema-valid success; every failed workflow has no END execution. Semantic matches reported separately, not a benchmark gate.",
    }
    report_file = evidence / "structured-live-smoke.json"

    def save():
        report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

    save()
    with (evidence / "structured-live-app.log").open("w") as log:
        app = subprocess.Popen(["java", "-Xmx512m", "-jar",
            str(ROOT / "backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar"),
            "--server.address=127.0.0.1"], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(120):
                if app.poll() is not None:
                    raise RuntimeError("isolated application exited")
                try:
                    request("GET", "/health")
                    break
                except OSError:
                    time.sleep(1)
            else:
                raise RuntimeError("isolated app health timeout")
            provider = request("POST", "/providers", {
                "name": "structured-live", "type": "OPENAI_COMPATIBLE",
                "baseUrl": "http://127.0.0.1:11434/v1", "auth": {"credentialRef": "env:STRUCTURED_LIVE_KEY"},
                "models": [{"displayName": "Local structured smoke", "modelId": "qwen2.5:0.5b",
                            "enabled": True, "isDefault": True}]})
            schema = {"type": "object", "properties": {
                "city": {"type": "string", "maxLength": 40}, "count": {"type": "number"},
                "urgent": {"type": "boolean"},
                "tags": {"type": "array", "maxItems": 3, "items": {"type": "string", "maxLength": 40}}},
                "required": ["city", "count", "urgent", "tags"], "additionalProperties": False}
            nodes = [
                {"nodeKey": "start", "type": "START", "name": "Start", "config": {}},
                {"nodeKey": "llm", "type": "LLM", "name": "Real local model", "config": {
                    "providerId": provider, "modelId": "qwen2.5:0.5b", "temperature": 0,
                    "maxOutputTokens": 512, "systemPrompt": "Extract the supplied values exactly. Return JSON only.",
                    "prompt": "{{start.userMessage}}", "outputSchema": schema}},
                {"nodeKey": "end", "type": "END", "name": "End", "config": {"output": "{{llm.result}}"}}]
            edges = [{"edgeKey": "e1", "sourceNodeKey": "start", "targetNodeKey": "llm", "defaultBranch": False},
                     {"edgeKey": "e2", "sourceNodeKey": "llm", "targetNodeKey": "end", "defaultBranch": False}]
            workflow = request("POST", "/workflows", {"name": "structured-live-fixed-six", "schemaVersion": 1,
                                                        "nodes": nodes, "edges": edges})
            version = request("POST", f"/workflows/{workflow}/versions")
            report.update(workflowId=workflow, workflowVersionId=version["id"], schema=schema)
            samples = [
                ("city Paris; count 2; urgent true; tags travel, work.",
                 {"city": "Paris", "count": 2, "urgent": True, "tags": ["travel", "work"]}),
                ("city Tokyo; count 0; urgent false; tags none (empty array).",
                 {"city": "Tokyo", "count": 0, "urgent": False, "tags": []}),
                ("city Lima; count 1.5; urgent false; tags review.",
                 {"city": "Lima", "count": 1.5, "urgent": False, "tags": ["review"]})]
            for repetition in range(2):
                for sample_no, (text, expected) in enumerate(samples):
                    started = time.monotonic()
                    run = request("POST", f"/workflow-versions/{version['id']}/runs", {"input": text})
                    success = run["status"] == "SUCCEEDED"
                    actual = json.loads(run["output"]) if success else None
                    row = {"sample": sample_no + 1, "repetition": repetition + 1, "input": text,
                           "runId": run["id"], "status": run["status"],
                           "latencyMs": round((time.monotonic() - started) * 1000),
                           "expected": expected, "actual": actual, "semanticMatch": actual == expected,
                           "nodes": [{"nodeKey": n["nodeKey"], "status": n["status"],
                                      "errorMessage": n.get("errorMessage")} for n in run["nodes"]]}
                    report["samples"].append(row)
                    save()  # Retain every attempt before asserting anything about it.
                    if success:
                        assert len(run["nodes"]) == 3 and run["nodes"][-1]["nodeKey"] == "end"
                    else:
                        assert run["status"] in {"FAILED", "TIMED_OUT"}
                        assert all(n["nodeKey"] != "end" for n in run["nodes"])
            successes = sum(r["status"] == "SUCCEEDED" for r in report["samples"])
            report.update(schemaValidSuccesses=successes, failedAttempts=6 - successes,
                          observedFailureRate=(6 - successes) / 6,
                          semanticMatches=sum(r["semanticMatch"] for r in report["samples"]))
            assert len(report["samples"]) == 6 and successes > 0, "no usable structured sample"
            report["result"] = "passed"
            save()
            print(json.dumps(report, ensure_ascii=False))
        except BaseException as failure:
            report.update(result="failed", failureType=type(failure).__name__)
            save()
            raise
        finally:
            if app.poll() is None:
                app.terminate()
                try:
                    app.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    app.kill()
                    app.wait(timeout=5)


if __name__ == "__main__":
    main()
