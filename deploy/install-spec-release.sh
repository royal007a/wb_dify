#!/bin/sh
# Upgrade only the existing single-instance Hify installation. Never provision keys.
set -eu
release=${1:?absolute release directory required}
health_attempts=${HIFY_DEPLOY_HEALTH_ATTEMPTS:-60}
case "$health_attempts" in ''|*[!0-9]*) exit 2 ;; esac
if ! test "$health_attempts" -ge 1 || ! test "$health_attempts" -le 300; then exit 2; fi
case "$release" in /opt/hify/releases/spec-verify-*) ;; *) exit 2 ;; esac
case "$release" in *..*|*[!a-zA-Z0-9/_-]*) exit 2 ;; esac
test "$(id -u)" = 0
jar=/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar
dist=/opt/hify/frontend/dist
snippet=/etc/nginx/snippets/hify-path.conf
key=/etc/hify/mcp-credentials.env
test -s "$jar"
test -f "$dist/index.html"
test -f "$snippet"
test -s "$key"
test "$(stat -c %a "$key")" = 600
test -f /etc/systemd/system/hify.service.d/20-mcp-credentials.conf
key_identity=$(stat -c '%i:%s:%Y:%a:%U' "$key")
test ! -e "$release/previous.jar"
test "$(df -Pk /opt/hify | awk 'NR==2 {print $4}')" -ge 800000
assert_no_running() {
  test "$(runuser -u postgres -- psql -d hify -Atqc "SELECT (SELECT count(*) FROM agent_runs WHERE state='RUNNING') + (SELECT count(*) FROM workflow_runs WHERE status='RUNNING')")" = 0
}
assert_no_running
umask 077
chmod 700 "$release"
(cd "$release" && sha256sum -c release.sha256)
# Archive produced by this repository contains only the three explicitly listed roots.
tar -tzf "$release/release.tar.gz" | awk '
  /(^\/|\.\.)/ {bad=1}
  !/^(backend\/hify-app\/target\/hify-app-0.1.0-SNAPSHOT.jar|frontend\/dist\/|deploy\/nginx-path.conf)/ {bad=1}
  END {exit bad}'
mkdir "$release/incoming"
tar -xzf "$release/release.tar.gz" -C "$release/incoming"
test -s "$release/incoming/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar"
test -f "$release/incoming/frontend/dist/index.html"
test -f "$release/incoming/deploy/nginx-path.conf"
cp -p "$jar" "$release/previous.jar"
cp -a "$dist" "$release/previous-dist"
cp -p "$snippet" "$release/previous-nginx.conf"
cutover=0
nginx_changed=0
on_exit() {
  result=$?
  # Cleanup must survive stderr EIO/EBADF (for example after SSH disconnect).
  # Preserve the original failure even if recovery or diagnostics also fail.
  set +e
  trap - EXIT HUP INT TERM
  if [ "$result" -ne 0 ]; then
    if [ "$nginx_changed" -eq 1 ]; then
      if cp -p "$release/previous-nginx.conf" "$snippet" && nginx -t; then
        systemctl reload nginx || true
      fi
    fi
    if [ "$cutover" -eq 0 ]; then
      systemctl start hify || true
      printf 'Upgrade stopped before application replacement; attempted old-service restart. Backup: %s\n' "$release" >&2 || true
    else
      systemctl stop hify || true
      printf 'Upgrade halted. DB/key preserved. Assess schema/new rows before reverting application. Backup: %s\n' "$release" >&2 || true
    fi
  fi
  exit "$result"
}
trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
# Take a consistent pre-migration backup with the old writer stopped.
systemctl stop hify
test "$(systemctl is-active hify || true)" = inactive
# Recheck after the old writer has stopped: preflight is not an admission lock.
# Unsettled Chat or direct Workflow work must be recovered by the old service,
# not migrated here. This can interrupt a late arrival; it is not zero downtime.
assert_no_running
runuser -u postgres -- pg_dump --format=custom hify > "$release/database-before.dump"
test -s "$release/database-before.dump"
pg_restore --list "$release/database-before.dump" > /dev/null
cutover=1
install -m 644 "$release/incoming/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar" "$jar.next"
mv "$jar.next" "$jar"
systemctl start hify
healthy=0
for attempt in $(seq 1 "$health_attempts"); do
  if curl -fs --max-time 2 http://127.0.0.1:28080/api/v1/health > /dev/null; then healthy=1; break; fi
  sleep 1
done
test "$healthy" = 1
test "$(runuser -u postgres -- psql -d hify -Atqc "SELECT max(version::int)=23 AND bool_and(success) FROM flyway_schema_history")" = t
nginx_changed=1
install -m 644 "$release/incoming/deploy/nginx-path.conf" "$snippet"
nginx -t
systemctl reload nginx
# Keep old hashes for already-open tabs. Publish entrypoint last.
cp -a "$release/incoming/frontend/dist/assets/." "$dist/assets/"
install -m 644 "$release/incoming/frontend/dist/index.html" "$dist/index.html.next"
mv "$dist/index.html.next" "$dist/index.html"
test "$(stat -c '%i:%s:%Y:%a:%U' "$key")" = "$key_identity"
systemctl is-active hify
# All actual success checks are complete. Diagnostic output below may fail or
# receive a signal; that must not stop a healthy published release.
trap - EXIT HUP INT TERM
sha256sum "$jar" "$snippet" "$dist/index.html"
runuser -u postgres -- psql -d hify -Atqc "SELECT version,success FROM flyway_schema_history WHERE version IN ('21','22','23') ORDER BY installed_rank"
printf 'Upgrade healthy; backup=%s; key metadata unchanged\n' "$release"
