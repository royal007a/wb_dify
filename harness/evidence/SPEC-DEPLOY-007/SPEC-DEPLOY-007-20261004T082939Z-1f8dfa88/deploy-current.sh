#!/bin/sh
set -eu
cd /Users/weberzhao/hify
bundle=/tmp/hify-redeploy-20261004.C6ny1e
evidence=$(node -e 'const s=require("./harness/state.json");if(s.currentTaskId!=="SPEC-DEPLOY-007")process.exit(2);console.log(s.evidencePath)')
evidence=/Users/weberzhao/hify/$evidence
gate=harness/evidence/SPEC-RELEASE-VERIFY-001/SPEC-RELEASE-VERIFY-001-20261004T081503Z-5ee00fdd
node -e 'const v=require("./"+process.argv[1]+"/verification.json");if(v.result!=="passed"||v.scopes.length!==6||v.headCommit!=="42db7278c61f9dcd49f505654341b7f1fceeafc9")process.exit(2)' "$gate"
git diff --quiet 42db727 -- backend frontend deploy
test "$(shasum -a 256 deploy/install-spec-release.sh | cut -d ' ' -f1)" = 93352ed7c3d9eae0b31e9b5a1dc7daed5c3c784d8d4ad4a82e0d481bb74cbf1a
printf 'Building verified source (skipTests is packaging only)\n'
mvn -B -DskipTests -f backend/pom.xml package > "$evidence/package.log" 2>&1
(cd frontend && VITE_BASE_PATH=/hify/ VITE_API_BASE_URL=/hify/api npm run build) > "$evidence/build-prefix.log" 2>&1
# This reviewed installer accepts V23 only. Do not run it with a future schema.
python3 - <<'PY'
import re, zipfile
with zipfile.ZipFile('backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar') as jar:
    versions={int(m[1]) for n in jar.namelist() if (m:=re.fullmatch(r'BOOT-INF/classes/db/migration/V(\d+)__[^/$]+\.(?:class|sql)',n))}
assert versions == set(range(1,24)), versions
print('Artifact migrations: V1..V23, no new migration')
PY
cp deploy/install-spec-release.sh deploy/smoke-upload.py "$bundle/"
COPYFILE_DISABLE=1 tar -czf "$bundle/release.tar.gz" backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar frontend/dist/ deploy/nginx-path.conf
(cd "$bundle" && shasum -a 256 release.tar.gz install-spec-release.sh smoke-upload.py > release.sha256)
shasum -a 256 backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar frontend/dist/index.html deploy/nginx-path.conf deploy/install-spec-release.sh "$bundle/release.tar.gz" > "$evidence/local-artifacts.log"
cp "$bundle/smoke-current.py" "$evidence/smoke-current.py"
cp "$bundle/deploy-current.sh" "$evidence/deploy-current.sh"
printf 'Remote preflight (no credentials read)\n'
ssh -o BatchMode=yes -o ConnectTimeout=10 root@118.196.123.132 /bin/sh > "$evidence/remote-preflight.log" 2>&1 <<'REMOTE'
set -eu
release=/opt/hify/releases/spec-verify-20261004-42db727
test ! -e "$release"
systemctl is-active --quiet hify
df -Pk /opt/hify
test "$(df -Pk /opt/hify | awk 'NR==2 {print $4}')" -ge 1100000
test "$(runuser -u postgres -- psql -d hify -Atqc "SELECT (SELECT count(*) FROM agent_runs WHERE state='RUNNING')+(SELECT count(*) FROM workflow_runs WHERE status='RUNNING')+(SELECT count(*) FROM document_index_tasks WHERE state IN ('PENDING','RUNNING'))")" = 0
test "$(runuser -u postgres -- psql -d hify -Atqc 'SELECT max(version::int)=23 AND bool_and(success) FROM flyway_schema_history')" = t
stat -c '%i:%s:%Y:%a:%U' /etc/hify/mcp-credentials.env
install -d -m 700 "$release"
REMOTE
printf 'Uploading completed artifacts\n'
scp -q "$bundle/release.tar.gz" "$bundle/install-spec-release.sh" "$bundle/smoke-upload.py" "$bundle/release.sha256" root@118.196.123.132:/opt/hify/releases/spec-verify-20261004-42db727/
printf 'Starting durable systemd upgrade\n'
ssh -o BatchMode=yes -o ConnectTimeout=10 root@118.196.123.132 /bin/sh > "$evidence/remote-upgrade.log" 2>&1 <<'REMOTE'
set -eu
cd /opt/hify/releases/spec-verify-20261004-42db727
sha256sum -c release.sha256
test "$(runuser -u postgres -- psql -d hify -Atqc "SELECT count(*) FROM document_index_tasks WHERE state IN ('PENDING','RUNNING')")" = 0
systemd-run --unit=hify-upgrade-20261004-0830 --property=Type=oneshot --setenv=HIFY_DEPLOY_HEALTH_ATTEMPTS=120 /bin/sh /opt/hify/releases/spec-verify-20261004-42db727/install-spec-release.sh /opt/hify/releases/spec-verify-20261004-42db727
for attempt in $(seq 1 300); do
  state=$(systemctl show hify-upgrade-20261004-0830 -p ActiveState --value)
  case "$state" in
    inactive) test "$(systemctl show hify-upgrade-20261004-0830 -p Result --value)" = success; break ;;
    failed) systemctl show hify-upgrade-20261004-0830 -p Result -p ExecMainStatus; exit 1 ;;
  esac
  sleep 1
done
test "$(systemctl show hify-upgrade-20261004-0830 -p ActiveState --value)" = inactive
systemctl show hify-upgrade-20261004-0830 -p Result -p ExecMainStatus -p ActiveEnterTimestamp -p InactiveEnterTimestamp
systemctl is-active --quiet hify
curl -fs --max-time 10 http://127.0.0.1:28080/api/v1/health
sha256sum /opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar /opt/hify/frontend/dist/index.html /etc/nginx/snippets/hify-path.conf install-spec-release.sh
stat -c '%i:%s:%Y:%a:%U' /etc/hify/mcp-credentials.env
stat -c 'backupBytes=%s backupMode=%a' database-before.dump
pg_restore --list database-before.dump >/dev/null
REMOTE
printf 'Deployed; checking current API behavior\n'
python3 "$bundle/smoke-current.py" > "$evidence/smoke-current.json"
python3 deploy/smoke-upload.py --api https://118.196.123.132/hify/api/v1 --self-signed-test > "$evidence/smoke-prefix.json"
ssh -o BatchMode=yes -o ConnectTimeout=10 root@118.196.123.132 'python3 /opt/hify/releases/spec-verify-20261004-42db727/smoke-upload.py --api http://127.0.0.1:28080/api/v1' > "$evidence/smoke-direct.json"
printf 'Running live browser checks\n'
(cd frontend && E2E_BASE_URL=https://118.196.123.132/hify/ E2E_IGNORE_HTTPS_ERRORS=true MCP_TOKEN_LIVE=1 ./node_modules/.bin/playwright test e2e/chat.spec.ts e2e/chat-time.spec.ts e2e/mcp-token-live.spec.ts --workers=1 --trace=off) > "$evidence/browser-live.log" 2>&1
ssh -o BatchMode=yes -o ConnectTimeout=10 root@118.196.123.132 /bin/sh > "$evidence/remote-final.log" 2>&1 <<'REMOTE'
set -eu
systemctl is-active hify
df -Pk /opt/hify
runuser -u postgres -- psql -d hify -Atqc "SELECT json_build_object('runningRuns',(SELECT count(*) FROM agent_runs WHERE state='RUNNING'),'runningWorkflows',(SELECT count(*) FROM workflow_runs WHERE status='RUNNING'),'activeIndexTasks',(SELECT count(*) FROM document_index_tasks WHERE state IN ('PENDING','RUNNING')),'schemaVersion',(SELECT max(version::int) FROM flyway_schema_history),'allMigrationsSucceeded',(SELECT bool_and(success) FROM flyway_schema_history));"
stat -c '%i:%s:%Y:%a:%U' /etc/hify/mcp-credentials.env
sha256sum /opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar /opt/hify/frontend/dist/index.html /etc/nginx/snippets/hify-path.conf
REMOTE
printf 'Remote smoke checks succeeded; local closing gates follow\n'
