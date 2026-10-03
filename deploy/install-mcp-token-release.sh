#!/bin/sh
# Scoped release installer for the existing 132 systemd Hify deployment.
# Run as root with an absolute /opt/hify/releases/mcp-token-* directory.
set -eu
release=${1:?release directory required}
case "$release" in /opt/hify/releases/mcp-token-*) ;; *) exit 2 ;; esac
case "$release" in *..*|*/../*) exit 2 ;; esac
jar=/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar
dist=/opt/hify/frontend/dist
test "$(id -u)" = 0
test -f "$jar"
test -f "$dist/index.html"
test ! -e "$release/previous.jar"
mkdir -p "$release/incoming"
chmod 700 "$release"
tar -xzf "$release/release.tar.gz" -C "$release/incoming"
test -f "$release/incoming/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar"
test -f "$release/incoming/frontend/dist/index.html"
test -f "$release/incoming/deploy/hify-mcp-token.conf"
cp -p "$jar" "$release/previous.jar"
cp -a "$dist" "$release/previous-dist"
umask 077
runuser -u postgres -- pg_dump --format=custom hify > "$release/database-before.dump"
test -s "$release/database-before.dump"

# Generate, never display or replace, the long-lived master key. Abort if the DB
# already contains encrypted credentials but their key file has gone missing.
python3 - <<'PY'
import base64
import os
from pathlib import Path
import secrets
import subprocess

key_file = Path('/etc/hify/mcp-credentials.env')
main = Path('/etc/hify/hify.env').read_text()
if any(line.startswith('HIFY_MCP_MASTER_KEY=') for line in main.splitlines()):
    raise SystemExit('Existing master key in main environment; reconcile manually without exporting it')
if key_file.exists():
    value = key_file.read_text().strip().removeprefix('HIFY_MCP_MASTER_KEY=')
    try:
        assert len(base64.b64decode(value, validate=True)) == 32
    except Exception:
        raise SystemExit('Existing key file is invalid; do not overwrite it')
else:
    psql = ['runuser', '-u', 'postgres', '--', 'psql', '-d', 'hify', '-Atqc']
    table = subprocess.check_output(psql + ["SELECT to_regclass('public.mcp_credentials') IS NOT NULL"], text=True).strip()
    if table == 't':
        count = subprocess.check_output(psql + ['SELECT count(*) FROM mcp_credentials'], text=True).strip()
        if count != '0':
            raise SystemExit('Encrypted credentials exist but key is missing; restore the key first')
    fd = os.open(key_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as out:
        out.write('HIFY_MCP_MASTER_KEY=' + base64.b64encode(secrets.token_bytes(32)).decode() + '\n')
os.chmod(key_file, 0o600)
print('Master key file ready (value not displayed)')
PY

mkdir -p /etc/systemd/system/hify.service.d
dropin=/etc/systemd/system/hify.service.d/20-mcp-credentials.conf
if [ -f "$dropin" ]; then
  cmp "$release/incoming/deploy/hify-mcp-token.conf" "$dropin"
else
  install -m 644 "$release/incoming/deploy/hify-mcp-token.conf" "$dropin"
fi
systemctl daemon-reload
rollback() {
  result=$?
  if [ "$result" -ne 0 ]; then
    systemctl stop hify || true
    # No automatic DB restore or key deletion. Only restore pre-cutover artifacts.
    cp -p "$release/previous.jar" "$jar"
    cp -a "$release/previous-dist/." "$dist/"
    systemctl start hify || true
    printf 'Deployment failed; prior artifacts restored; DB/key retained. Backup: %s\n' "$release" >&2
  fi
  exit "$result"
}
trap rollback EXIT
systemctl stop hify
install -m 644 "$release/incoming/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar" "$jar.next"
mv "$jar.next" "$jar"
systemctl start hify
healthy=0
for attempt in $(seq 1 50); do
  if curl -fs --max-time 2 http://127.0.0.1:28080/api/v1/health > /dev/null; then healthy=1; break; fi
  sleep 1
done
test "$healthy" -eq 1
runuser -u postgres -- psql -d hify -Atqc "SELECT version || ':' || success FROM flyway_schema_history WHERE version='20'"
# Publish index last, keeping old hashed assets for existing browser tabs.
cp -a "$release/incoming/frontend/dist/assets/." "$dist/assets/"
install -m 644 "$release/incoming/frontend/dist/index.html" "$dist/index.html.next"
mv "$dist/index.html.next" "$dist/index.html"
systemctl is-active hify
curl -fsS http://127.0.0.1:28080/api/v1/health
printf '\nBackup directory: %s\n' "$release"
trap - EXIT
