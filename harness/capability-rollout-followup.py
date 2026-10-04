#!/usr/bin/env python3
"""Post-install validation only: never install, migrate, restart, or reconfigure."""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
state = json.loads((root/'harness/state.json').read_text())
assert state['currentTaskId'] == 'CAPABILITY-ROLLOUT-001'
evidence = root/state['evidencePath']
first = root/'harness/evidence/CAPABILITY-ROLLOUT-001/CAPABILITY-ROLLOUT-001-20261004T164021Z-fac60c69'
artifacts = json.loads((first/'local-artifacts.json').read_text())['artifacts']
subprocess.run(['git','diff','--quiet','a510191','--','backend','frontend','deploy'],cwd=root,check=True)
failures = []
for name, command in [('capability',['python3','harness/capability-remote-smoke.py']),
                      ('browser',['node','harness/capability-browser-smoke.cjs'])]:
    with (evidence/(name+'.log')).open('w') as log:
        result = subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=240)
    if result.returncode:
        failures.append(name)
    elif name == 'capability':
        value = json.loads((evidence/(name+'.log')).read_text())
        (evidence/'smoke-capability.json').write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
    print(name+' exit '+str(result.returncode),flush=True)
targets = {'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar':'/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar',
           'frontend/dist/index.html':'/opt/hify/frontend/dist/index.html',
           'deploy/nginx-path.conf':'/etc/nginx/snippets/hify-path.conf'}
program = '''set -eu
systemctl is-active --quiet hify
curl -fs --max-time 10 http://127.0.0.1:28080/api/v1/health
printf '\n'
df -Pk /opt/hify
runuser -u postgres -- psql -d hify -Atqc "SELECT json_build_object('runningRuns',(SELECT count(*) FROM agent_runs WHERE state='RUNNING'),'runningWorkflows',(SELECT count(*) FROM workflow_runs WHERE status='RUNNING'),'activeIndexTasks',(SELECT count(*) FROM document_index_tasks WHERE state IN ('PENDING','RUNNING')),'schemaVersion',(SELECT max(version::int) FROM flyway_schema_history),'allMigrationsSucceeded',(SELECT bool_and(success) FROM flyway_schema_history));"
stat -c 'keyMetadata=%i:%s:%Y:%a:%U' /etc/hify/mcp-credentials.env
'''
program += '\n'.join('sha256sum '+target for target in targets.values())+'\n'
result = subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','root@118.196.123.132','/bin/sh'],
                        input=program,text=True,capture_output=True,check=True,timeout=30)
(evidence/'remote-final.log').write_text(result.stdout+result.stderr)
for source,target in targets.items():
    assert artifacts[source]+'  '+target in result.stdout
stats = next(json.loads(line) for line in result.stdout.splitlines() if line.startswith('{"runningRuns"'))
assert stats == {'runningRuns':0,'runningWorkflows':0,'activeIndexTasks':0,'schemaVersion':24,'allMigrationsSucceeded':True}
before = [line for line in (first/'remote-preflight.log').read_text().splitlines() if line.startswith('keyMetadata=')]
after = [line for line in result.stdout.splitlines() if line.startswith('keyMetadata=')]
assert len(before) == 1 and before == after
(evidence/'followup.json').write_text(json.dumps({'firstRun':str(first.relative_to(root)),
    'installationRepeated':False,'failures':failures,'stats':stats,'artifactShaMatch':True,
    'keyMetadataUnchanged':True,'secondModelCall':'Explicit rerun of corrected smoke; first model assertion already passed.'},indent=2)+'\n')
assert not failures, failures
print('Post-install checks passed; no installation or configuration repeated.',flush=True)
