#!/usr/bin/env python3
"""Run synthetic semantic smoke against a fresh, loopback-only application.

Uses the existing local Ollama model; never creates or changes a model credential.
The temporary application owns an in-memory database and is stopped on every exit.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
state = json.loads((ROOT / "harness/state.json").read_text())
if state.get("currentTaskId") not in {"SEMANTIC-001", "CAPABILITY-VERIFY-001"}:
    raise SystemExit("A supported semantic verification task must be active")
evidence = ROOT / state["evidencePath"]
with socket.socket() as probe:
    probe.bind(("127.0.0.1", 28082))
subprocess.run(["mvn", "-B", "-pl", "hify-app", "-am", "-DskipTests", "package"],
               cwd=ROOT / "backend", check=True)
env = dict(os.environ)
env.update(HIFY_DB_URL="jdbc:h2:mem:semantic_live;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
           HIFY_DB_USERNAME="sa", HIFY_DB_PASSWORD="", HIFY_PORT="28082",
           HIFY_PROVIDER_ALLOW_PRIVATE="true", SEMANTIC_LIVE_KEY="synthetic-local-ollama",
           HIFY_CREDENTIAL_REFERENCE_BINDINGS=json.dumps({
               "env:SEMANTIC_LIVE_KEY": ["http://127.0.0.1:11434/v1"]}))
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with (evidence / "live-app.log").open("w") as log:
    app = subprocess.Popen(["java", "-Xmx512m", "-jar",
        str(ROOT / "backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar"),
        "--server.address=127.0.0.1"], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(120):
            if app.poll() is not None:
                raise RuntimeError("isolated application exited before healthy")
            try:
                with opener.open("http://127.0.0.1:28082/api/v1/health", timeout=2) as r:
                    if r.status == 200:
                        break
            except OSError:
                pass
            time.sleep(1)
        else:
            raise RuntimeError("isolated application did not become healthy")
        script = ROOT / "harness/evidence/SEMANTIC-001/SEMANTIC-001-20261004T104417Z-61ed4dde/live-semantic-smoke.py"
        result = subprocess.run(["python3", str(script), "--credential-ref", "env:SEMANTIC_LIVE_KEY"],
                                cwd=ROOT, text=True, capture_output=True, timeout=480)
        if result.returncode:
            # Only the synthetic smoke's diagnostics; application log stays gitignored.
            raise RuntimeError("semantic smoke failed: " + result.stderr[-3000:])
        output = json.loads(result.stdout)
        output["headCommit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        output["backendTree"] = subprocess.check_output(["git", "rev-parse", "HEAD:backend"], cwd=ROOT, text=True).strip()
        output["sourceDiffEmpty"] = not subprocess.check_output(["git", "diff", "HEAD", "--", "backend", "frontend"], cwd=ROOT)
        (evidence / "live-semantic-smoke.json").write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(output, ensure_ascii=False))
    finally:
        if app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=20)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait(timeout=5)
