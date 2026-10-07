#!/usr/bin/env python3
"""Explicitly authorized, source-bound original Hify rollout. No credential reads."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
def require(condition, message):
    if not condition:
        raise RuntimeError(message)

state = json.loads((ROOT / 'harness/state.json').read_text())
if state.get('currentTaskId') != 'CONTEXT-ROLLOUT-001':
    raise SystemExit('CONTEXT-ROLLOUT-001 must be active through run-task.sh')
evidence = ROOT / state['evidencePath']
tasks = json.loads((ROOT / 'harness/tasks.json').read_text())['tasks']
accepted = next(t for t in tasks if t['id'] == 'OBS-CORRELATION-001')
require(accepted['status'] == 'completed', 'Current-source OBS gate must be completed')
record = accepted['evidence'][-1]
gate = ROOT / record['path']
verification = json.loads((gate / 'verification.json').read_text())
require(verification['result'] == 'passed' and verification['schemaVersion'] == 3, 'Gate must pass schema 3')
require(set(verification['scopes']) == {'harness','migration','backend','runtime','eval','frontend'}, 'All six scopes required')
require(hashlib.sha256((gate / 'verification.json').read_bytes()).hexdigest() == record['verificationSha256'], 'Gate SHA mismatch')
subprocess.run(['python3', 'harness/harness.py', 'validate'], cwd=ROOT, check=True)
tested_head = verification['headCommit']
require(tested_head == record['headCommit'], 'Gate head mismatch')
subprocess.run(['git','diff','--quiet',tested_head,'--','backend','frontend','deploy'],cwd=ROOT,check=True)
require(not subprocess.check_output(['git','ls-files','--others','--exclude-standard','--','backend','frontend','deploy'],cwd=ROOT), 'Untracked release source')
release = '/opt/hify/releases/spec-verify-20261007-context-' + tested_head[:8]
unit = 'hify-upgrade-20261007-context-' + tested_head[:8]
ssh = ['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','root@118.196.123.132','/bin/sh']

def command(args, name, *, text=None, cwd=ROOT, env=None, timeout=1200):
    try:
        with (evidence / (name+'.log')).open('w') as log:
            subprocess.run(args,input=text,text=True,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,
                           check=True,timeout=timeout)
    finally:
        if name in ('remote-preflight', 'remote-install', 'remote-final'):
            try:
                observations = {stage:(evidence/(stage+'.log')).read_text()
                                for stage in ('remote-preflight','remote-install','remote-final')
                                if (evidence/(stage+'.log')).is_file()}
                (evidence/'remote-observations.json').write_text(json.dumps({
                    'source':'publisher command observations, not independent verification',
                    'backupValidation':'pg_restore --list only; no restore drill',
                    'observations':observations},indent=2)+'\n')
            except OSError:
                print('Could not persist observation JSON; raw command logs retained where writable',flush=True)
    print(name+' passed',flush=True)

command(['mvn','-B','-DskipTests','-f','backend/pom.xml','package'],'package')
command(['npm','run','build'],'build-prefix',cwd=ROOT/'frontend',
        env=dict(os.environ,VITE_BASE_PATH='/hify/',VITE_API_BASE_URL='/hify/api'))
# Retain this exact local bundle for audit/recovery; never reuse or remove others.
bundle = Path(tempfile.mkdtemp(prefix='hify-context-rollout-'))
command(['tar','-czf',str(bundle/'release.tar.gz'),
    'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar','frontend/dist/','deploy/nginx-path.conf'],
    'bundle',env=dict(os.environ,COPYFILE_DISABLE='1'))
for name in ['install-spec-release.sh','smoke-upload.py']:
    (bundle/name).write_bytes((ROOT/'deploy'/name).read_bytes())
manifest = {name:hashlib.sha256((bundle/name).read_bytes()).hexdigest()
            for name in ['release.tar.gz','install-spec-release.sh','smoke-upload.py']}
(bundle/'release.sha256').write_text(''.join(digest+'  '+name+'\n' for name,digest in manifest.items()))
artifacts = {str(p):hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in [
    Path('backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar'),Path('frontend/dist/index.html'),
    Path('deploy/nginx-path.conf'),Path('deploy/install-spec-release.sh')]}
frontend_files = {p.relative_to(ROOT/'frontend/dist').as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in (ROOT/'frontend/dist').rglob('*') if p.is_file()}
(evidence/'local-artifacts.json').write_text(json.dumps({'gate':str(gate.relative_to(ROOT)),
    'testedHead':verification['headCommit'],'release':release,'unit':unit,'localBundle':str(bundle),
    'artifacts':artifacts,'uploadFiles':manifest,'frontendFiles':frontend_files,
    'meaning':'Rebuilt from the tested source trees; /hify frontend build settings. Not identical test-time binaries.'},indent=2)+'\n')
new_bytes=(ROOT/'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar').stat().st_size+sum(
    p.stat().st_size for p in (ROOT/'frontend/dist').rglob('*') if p.is_file())
# Include incoming AND the replacement .next/static-copy peak, not just tar size.
staging_kib=((bundle/'release.tar.gz').stat().st_size+2*new_bytes+1023)//1024
preflight=f'''set -eu
test ! -e {release}
test "$(systemctl show {unit} -p LoadState --value)" = not-found
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3,11) else 1)'
nginx -t
systemctl is-active --quiet hify
systemctl show hify -p MainPID --value | sed 's/^/previousPid=/'
test "$(runuser -u postgres -- psql -d hify -Atqc "SELECT max(version::integer)=24 AND bool_and(success) FROM flyway_schema_history")" = t
test "$(runuser -u postgres -- psql -d hify -Atqc "SELECT (SELECT count(*) FROM agent_runs WHERE state='RUNNING')+(SELECT count(*) FROM workflow_runs WHERE status='RUNNING')+(SELECT count(*) FROM document_index_tasks WHERE state IN ('PENDING','RUNNING'))")" = 0
test "$(runuser -u postgres -- psql -d hify -Atqc "SELECT count(*) FROM workflow_versions w WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(w.dsl_json::jsonb->'nodes') n WHERE upper(n->>'type')='START' AND (n->'config') ? 'inputs') AND NOT (COALESCE(w.dsl_json::jsonb->'publication','{{}}'::jsonb) ? 'inputSchemaFormat')")" = 0
old_kib=$(du -sk /opt/hify/frontend/dist /opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar | awk '{{s+=$1}} END {{print s}}')
db_bytes=$(runuser -u postgres -- psql -d hify -Atqc "SELECT pg_database_size('hify')")
free_kib=$(df -Pk /opt/hify | awk 'NR==2 {{print $4}}')
required_kib=$(( {staging_kib} + old_kib + (db_bytes+1023)/1024 + 800000 ))
printf 'freeKiB=%s requiredKiB=%s stagingKiB=%s oldKiB=%s dbBytes=%s\n' "$free_kib" "$required_kib" {staging_kib} "$old_kib" "$db_bytes"
test "$free_kib" -ge "$required_kib"
stat -c 'keyMetadata=%i:%s:%Y:%a:%U' /etc/hify/mcp-credentials.env
'''
command(ssh,'remote-preflight',text=preflight,timeout=60)
# Only after every read-only check succeeds create the new scoped directory.
command(ssh,'remote-directory',text=f'set -eu\ntest ! -e {release}\ninstall -d -m 700 {release}\n',timeout=30)
try:
    command(['scp','-q',*[str(bundle/name) for name in [*manifest,'release.sha256']],
             'root@118.196.123.132:'+release+'/'],'upload',timeout=120)
except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
    # No installer has been submitted yet. Remove only the four exact upload targets;
    # unexpected entries keep the directory in place for inspection, never recursive rm.
    try:
        command(ssh,'upload-cleanup',text=f'''set -eu
test ! -e {release}/previous.jar
rm -f {release}/release.tar.gz {release}/install-spec-release.sh {release}/smoke-upload.py {release}/release.sha256
rmdir {release}
''',timeout=30)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        print('Upload cleanup incomplete; retain release path for inspection', flush=True)
    raise
hashchecks='\n'.join(f'test "$(sha256sum {name} | cut -d " " -f1)" = {digest}' for name,digest in manifest.items())
targets={
    'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar':'/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar',
    'frontend/dist/index.html':'/opt/hify/frontend/dist/index.html',
    'deploy/nginx-path.conf':'/etc/nginx/snippets/hify-path.conf'}
artifact_checks='\n'.join(f'test "$(sha256sum {target} | cut -d " " -f1)" = {artifacts[source]}'
                          for source,target in targets.items())
command(ssh,'remote-install',text=f'''set -eu
cd {release}
{hashchecks}
sha256sum -c release.sha256
systemd-run --unit={unit} --property=Type=oneshot --setenv=HIFY_DEPLOY_HEALTH_ATTEMPTS=120 /bin/sh {release}/install-spec-release.sh {release}
for attempt in $(seq 1 360); do
  state=$(systemctl show {unit} -p ActiveState --value)
  case "$state" in
    inactive) test "$(systemctl show {unit} -p Result --value)" = success; break ;;
    failed) systemctl show {unit} -p Result -p ExecMainStatus; exit 1 ;;
  esac
  sleep 1
done
if [ "$(systemctl show {unit} -p ActiveState --value)" != inactive ]; then
  systemctl show {unit} -p ActiveState -p SubState -p Result -p ExecMainStatus
  printf 'Wait expired; oneshot may still be running. Inspect the existing unit; do not rerun deployment.\n' >&2
  exit 1
fi
test "$(systemctl show {unit} -p Result --value)" = success
systemctl show {unit} -p Result -p ExecMainStatus -p InactiveEnterTimestamp
systemctl is-active --quiet hify
curl -fs --max-time 10 http://127.0.0.1:28080/api/v1/health
{artifact_checks}
test "$(runuser -u postgres -- psql -d hify -Atqc "SELECT max(version::integer)=24 AND count(*)=24 AND bool_and(success) FROM flyway_schema_history")" = t
test -s database-before.dump
test "$(stat -c %a database-before.dump)" = 600
pg_restore --list database-before.dump >/dev/null
stat -c 'dumpBytes=%s dumpMode=%a' database-before.dump
stat -c 'keyMetadata=%i:%s:%Y:%a:%U' /etc/hify/mcp-credentials.env
sha256sum /opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar /opt/hify/frontend/dist/index.html /etc/nginx/snippets/hify-path.conf
''',timeout=480)
# No output/body from a real credential is ever retrieved. Smoke results contain
# only own synthetic IDs, Mock outputs, counters, and status codes.
checks=[('current',['python3','harness/evidence/SPEC-DEPLOY-007/SPEC-DEPLOY-007-20261004T082939Z-1f8dfa88/smoke-current.py']),
        ('prefix',['python3','deploy/smoke-upload.py','--api','https://118.196.123.132/hify/api/v1','--self-signed-test']),
        ('direct',ssh)]
failures=[]
for name,args in checks:
    try:
        command(args,'smoke-'+name,text=(f'python3 {release}/smoke-upload.py --api http://127.0.0.1:28080/api/v1\n' if name=='direct' else None))
        value=json.loads((evidence/('smoke-'+name+'.log')).read_text())
        (evidence/('smoke-'+name+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
    except (subprocess.CalledProcessError,subprocess.TimeoutExpired,json.JSONDecodeError) as error:
        failures.append({'check':name,'errorType':type(error).__name__})
        print('smoke-'+name+' failed; collecting remaining independent checks, not retrying',flush=True)
try:
    command(['node','harness/context-browser-smoke.cjs'],'browser-live')
except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
    failures.append({'check':'browser-live','errorType':type(error).__name__})
(evidence/'post-install-checks.json').write_text(json.dumps({'failedChecks':failures,
    'automaticRollback':False,'modelRetry':False},indent=2)+'\n')
command(ssh,'remote-final',text=r'''set -eu
systemctl is-active hify
pid=$(systemctl show hify -p MainPID --value)
test "$pid" -gt 0
printf 'runningPid=%s\n' "$pid"
python3 - "$pid" <<'PY'
import hashlib, json, os, sys
pid=sys.argv[1]
target='/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar'
args=open('/proc/'+pid+'/cmdline','rb').read().split(b'\0')
if target.encode() not in args:
    raise SystemExit('Running JVM command does not use expected jar')
digests=set()
for name in os.listdir('/proc/'+pid+'/fd'):
    fd='/proc/'+pid+'/fd/'+name
    try:
        if os.readlink(fd)==target:
            with open(fd,'rb') as f:
                digests.add(hashlib.file_digest(f,'sha256').hexdigest())
    except FileNotFoundError:
        continue
if len(digests)!=1:
    raise SystemExit('Cannot identify exactly one running jar SHA through open descriptors')
print('runningJarSha256='+next(iter(digests)))
PY
df -Pk /opt/hify
runuser -u postgres -- psql -d hify -Atqc "SELECT json_build_object('runningRuns',(SELECT count(*) FROM agent_runs WHERE state='RUNNING'),'runningWorkflows',(SELECT count(*) FROM workflow_runs WHERE status='RUNNING'),'activeIndexTasks',(SELECT count(*) FROM document_index_tasks WHERE state IN ('PENDING','RUNNING')),'schemaVersion',(SELECT max(version::int) FROM flyway_schema_history),'allMigrationsSucceeded',(SELECT bool_and(success) FROM flyway_schema_history));"
stat -c 'keyMetadata=%i:%s:%Y:%a:%U' /etc/hify/mcp-credentials.env
sha256sum /opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar /opt/hify/frontend/dist/index.html /etc/nginx/snippets/hify-path.conf
''',timeout=30)
def metadata(name):
    return [line for line in (evidence/(name+'.log')).read_text().splitlines() if line.startswith('keyMetadata=')]
remote_final=(evidence/'remote-final.log').read_text()
# Persist these deliberately narrow, credential-free command observations even
# if a later smoke assertion fails. Raw application logs are not collected.
observations = {name: (evidence/(name+'.log')).read_text()
                for name in ['remote-preflight','remote-install','remote-final']}
(evidence/'remote-observations.json').write_text(json.dumps({
    'source':'publisher command observations, not independent verification',
    'backupValidation':'pg_restore --list only; no restore drill',
    'observations':observations},indent=2)+'\n')
require(len(metadata('remote-preflight')) == 1, 'Missing key metadata')
require(metadata('remote-preflight') == metadata('remote-install') == metadata('remote-final'), 'key metadata changed')
before_pids=[line for line in observations['remote-preflight'].splitlines() if line.startswith('previousPid=')]
after_pids=[line for line in remote_final.splitlines() if line.startswith('runningPid=')]
require(len(before_pids)==len(after_pids)==1 and before_pids[0].split('=')[1]!=after_pids[0].split('=')[1], 'Running PID was not replaced')
require('runningJarSha256='+artifacts['backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar'] in remote_final.splitlines(), 'Running JVM jar SHA mismatch')
for source,target in targets.items():
    require(artifacts[source]+'  '+target in remote_final, 'installed artifact SHA differs')
stats=next(json.loads(line) for line in remote_final.splitlines() if line.startswith('{'))
require(stats == {'runningRuns':0,'runningWorkflows':0,'activeIndexTasks':0,'schemaVersion':24,'allMigrationsSucceeded':True}, 'Unexpected final runtime/schema state')
if failures:
    raise RuntimeError('Post-install checks failed; new release remains running. Inspect logs and schema compatibility before manual action: '+json.dumps(failures))
summary = {'testedHead': tested_head, 'gate':str(gate.relative_to(ROOT)), 'release':release,'unit':unit,
    'sourceTrees': {part:subprocess.check_output(['git','rev-parse',tested_head+':'+part],cwd=ROOT,text=True).strip() for part in ['backend','frontend','deploy']},
    'artifacts':artifacts, 'final':stats, 'keyMetadataUnchanged':True, 'automaticRollback':False,
    'limits':['Rebuilt from accepted source; not gate-time binaries','Backup list checked, no restore drill',
    'H1 messages/zero-call behavior proven locally; remote smoke uses Mock only',
    'No live embedding or new Workflow structured-output quality benchmark','TLS validation disabled in self-signed smoke/browser only'],
    'evidenceSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in evidence.glob('smoke-*.json')},
    'observations':{name:hashlib.sha256((evidence/name).read_bytes()).hexdigest() for name in ['remote-observations.json','browser-live.json']}}
(evidence/'deployment-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print('Remote source-bound rollout and scoped Mock smoke passed; closing gates follow.',flush=True)
