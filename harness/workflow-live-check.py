#!/usr/bin/env python3
"""Synthetic GET -> real local model, on an owned loopback app/H2 process only."""
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent.parent
state = json.loads((ROOT / "harness/state.json").read_text())
if state.get("currentTaskId") not in {"WORKFLOW-NODES-001", "CAPABILITY-VERIFY-001"}:
    raise SystemExit("A supported workflow verification task must be active")
evidence = ROOT / state["evidencePath"]
with socket.socket() as probe:
    probe.bind(("127.0.0.1", 28083))
subprocess.run(["mvn", "-B", "-pl", "hify-app", "-am", "-DskipTests", "package"], cwd=ROOT / "backend", check=True)

class ReadFixture(BaseHTTPRequestHandler):
    calls = 0
    def do_GET(self):
        if self.path != "/read":
            self.send_error(404)
            return
        ReadFixture.calls += 1
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"42")
    def log_message(self, *_):
        pass

fixture = ThreadingHTTPServer(("127.0.0.1", 0), ReadFixture)
threading.Thread(target=fixture.serve_forever, daemon=True).start()
endpoint = f"http://127.0.0.1:{fixture.server_port}/read"
env = dict(os.environ)
env.update(HIFY_DB_URL="jdbc:h2:mem:workflow_live;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
           HIFY_DB_USERNAME="sa", HIFY_DB_PASSWORD="", HIFY_PORT="28083",
           HIFY_PROVIDER_ALLOW_PRIVATE="true", WORKFLOW_LIVE_KEY="synthetic-local-ollama",
           HIFY_CREDENTIAL_REFERENCE_BINDINGS=json.dumps({"env:WORKFLOW_LIVE_KEY": ["http://127.0.0.1:11434/v1"]}),
           HIFY_WORKFLOW_HTTP_ALLOWED_ENDPOINTS=json.dumps([endpoint]))
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(method, path, body=None):
    req = urllib.request.Request("http://127.0.0.1:28083/api/v1" + path, method=method,
        data=None if body is None else json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with opener.open(req, timeout=70) as response:
        return json.load(response)["data"]

with (evidence / "workflow-live-app.log").open("w") as log:
    app = subprocess.Popen(["java", "-Xmx512m", "-jar", str(ROOT / "backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar"),
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
        provider = request("POST", "/providers", {"name": "workflow-live", "type": "OPENAI_COMPATIBLE",
            "baseUrl": "http://127.0.0.1:11434/v1", "auth": {"credentialRef": "env:WORKFLOW_LIVE_KEY"},
            "models": [{"displayName": "Local smoke", "modelId": "qwen2.5:0.5b", "enabled": True, "isDefault": True}]})
        nodes = [
            {"nodeKey": "start", "type": "START", "name": "开始", "config": {}},
            {"nodeKey": "read", "type": "API_CALL", "name": "合成只读源", "config": {"endpoint": endpoint}},
            {"nodeKey": "llm", "type": "LLM", "name": "真实模型", "config": {"providerId": provider, "modelId": "qwen2.5:0.5b",
             "systemPrompt": "Follow the user instruction exactly.", "prompt": "Repeat only these digits: {{read.result}}", "temperature": 0, "maxOutputTokens": 64}},
            {"nodeKey": "end", "type": "END", "name": "结束", "config": {"output": "{{llm.result}}"}}]
        edges = [{"edgeKey": f"e{i}", "sourceNodeKey": a, "targetNodeKey": b, "defaultBranch": False}
                 for i, (a, b) in enumerate(zip(["start", "read", "llm"], ["read", "llm", "end"]))]
        workflow = request("POST", "/workflows", {"name": "workflow-live", "schemaVersion": 1, "nodes": nodes, "edges": edges})
        version = request("POST", f"/workflows/{workflow}/versions")
        started = time.monotonic()
        result = request("POST", f"/workflow-versions/{version['id']}/runs", {"input": "synthetic"})
        assert result["status"] == "SUCCEEDED", result
        assert "42" in result["output"] and len(result["nodes"]) == 4 and ReadFixture.calls == 1, result
        output = {"result": "passed", "realModel": "qwen2.5:0.5b", "httpFixture": True, "getCalls": ReadFixture.calls,
                  "status": result["status"], "output": result["output"], "nodeTypes": [n["nodeType"] for n in result["nodes"]],
                  "latencyMs": round((time.monotonic()-started)*1000), "scope": "synthetic GET -> real local model; not quality/security benchmark or production deployment",
                  "headCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  "backendTree": subprocess.check_output(["git", "rev-parse", "HEAD:backend"], cwd=ROOT, text=True).strip(),
                  "sourceDiffEmpty": not subprocess.check_output(["git", "diff", "HEAD", "--", "backend", "frontend"], cwd=ROOT)}
        (evidence / "workflow-live-smoke.json").write_text(json.dumps(output, ensure_ascii=False, indent=2)+"\n")
        print(json.dumps(output, ensure_ascii=False))
    finally:
        fixture.shutdown()
        fixture.server_close()
        if app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=20)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait(timeout=5)
