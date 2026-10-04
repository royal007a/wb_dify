#!/usr/bin/env python3
"""Bounded release checks: owned H2 processes and controlled browser routes only."""
import hashlib
import json
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
state = json.loads((ROOT / 'harness/state.json').read_text())
if state.get('currentTaskId') != 'CAPABILITY-VERIFY-001':
    raise SystemExit('CAPABILITY-VERIFY-001 must be active through the atomic runner')
evidence = ROOT / state['evidencePath']

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

paths = ['backend', 'frontend', 'deploy']
diff = git('diff', 'HEAD', '--', *paths)
if diff or git('ls-files', '--others', '--exclude-standard', '--', *paths):
    raise SystemExit('Source tree must match HEAD')
identity = {'headCommit': git('rev-parse', 'HEAD').decode().strip(),
            **{p+'Tree': git('rev-parse', 'HEAD:'+p).decode().strip() for p in paths},
            'trackedSourceDiffSha256': hashlib.sha256(diff).hexdigest(),
            'sourcePaths': paths,
            'meaning': 'Exact tracked source before/after local live checks; no remote deployment.'}
(evidence / 'source-identity.json').write_text(json.dumps(identity, indent=2)+'\n')
for name in ['semantic', 'workflow']:
    with (evidence / (name+'-live-command.log')).open('w') as log:
        subprocess.run(['python3', str(ROOT / 'harness' / (name+'-live-check.py'))],
                       cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=900)
    print(name+' local real-model checks passed', flush=True)
env = dict(os.environ, E2E_BASE_URL='http://127.0.0.1:15173/')
with (evidence / 'browser-controlled.log').open('w') as log:
    subprocess.run(['./node_modules/.bin/playwright', 'test', 'e2e/chat-lifecycle.spec.ts',
                    'e2e/management.spec.ts', 'e2e/mcp-edit.spec.ts', 'e2e/workflow-inputs.spec.ts',
                    '--workers=1', '--trace=off'], cwd=ROOT / 'frontend', env=env,
                   stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
if git('diff', 'HEAD', '--', *paths) or git('rev-parse', 'HEAD').decode().strip() != identity['headCommit']:
    raise SystemExit('Source changed during live checks')
print('Controlled browser checks passed; full six-scope gates follow', flush=True)
