#!/bin/sh

set -u

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SCOPE=auto
BASE=""
EVIDENCE_DIR=""

usage() {
  cat <<'USAGE'
Usage: ./harness/verify.sh [--scope auto|all|backend,frontend,migration,runtime,eval,harness]
                           [--base <git-ref>] [--evidence-dir <path>]
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --scope) SCOPE=${2:?missing scope}; shift 2 ;;
    --base) BASE=${2:?missing base}; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR=${2:?missing evidence directory}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf '[ERROR] unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v git >/dev/null 2>&1 || { echo '[ERROR] git is required' >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo '[ERROR] python3 is required' >&2; exit 2; }

if [ -z "$EVIDENCE_DIR" ]; then
  EVIDENCE_DIR="$ROOT_DIR/harness/evidence/manual/$(date -u +%Y%m%dT%H%M%SZ)-$$"
elif [ "${EVIDENCE_DIR#/}" = "$EVIDENCE_DIR" ]; then
  EVIDENCE_DIR="$ROOT_DIR/$EVIDENCE_DIR"
fi
mkdir -p "$EVIDENCE_DIR"
RECORDS="$EVIDENCE_DIR/steps.tsv"
: >"$RECORDS"
OVERALL=0

add_scope() {
  case " $SCOPES " in
    *" $1 "*) ;;
    *) SCOPES="$SCOPES $1" ;;
  esac
}

detect_scopes() {
  changes_file="$EVIDENCE_DIR/changed-files.txt"
  : >"$changes_file"
  if [ -n "$BASE" ]; then
    git -C "$ROOT_DIR" diff --name-only "$BASE"...HEAD >>"$changes_file" 2>/dev/null || {
      printf '[ERROR] invalid --base ref: %s\n' "$BASE" >&2
      exit 2
    }
  fi
  git -C "$ROOT_DIR" diff --name-only HEAD >>"$changes_file"
  git -C "$ROOT_DIR" ls-files --others --exclude-standard >>"$changes_file"
  sort -u "$changes_file" -o "$changes_file"

  while IFS= read -r path; do
    [ -n "$path" ] || continue
    case "$path" in
      backend/*) add_scope backend ;;
      frontend/*) add_scope frontend ;;
      harness/*|exec-plans/*|docs/harness/*|AGENTS.md|plan.md|tech-debt-tracker.md) add_scope harness ;;
    esac
    case "$path" in
      backend/hify-app/src/main/resources/db/migration/*) add_scope migration ;;
    esac
    case "$path" in
      backend/hify-chat/src/main/java/com/hify/runtime/*|backend/hify-chat/src/test/java/com/hify/runtime/*|backend/hify-app/src/main/java/com/hify/api/RunController.java|backend/hify-app/src/test/java/com/hify/api/RunFlowIntegrationTest.java) add_scope runtime ;;
    esac
    case "$path" in
      backend/hify-chat/src/main/java/com/hify/intent/*|backend/hify-chat/src/test/java/com/hify/intent/*|backend/hify-chat/src/test/resources/intent/*|docs/evidence/intent-*) add_scope eval ;;
    esac
  done <"$changes_file"
  [ -n "${SCOPES# }" ] || add_scope harness
}

SCOPES=""
case "$SCOPE" in
  auto) detect_scopes ;;
  all) SCOPES=" harness migration backend runtime eval frontend" ;;
  *)
    old_ifs=$IFS
    IFS=','
    for requested in $SCOPE; do
      case "$requested" in
        backend|frontend|migration|runtime|eval|harness) add_scope "$requested" ;;
        *) printf '[ERROR] unsupported scope: %s\n' "$requested" >&2; exit 2 ;;
      esac
    done
    IFS=$old_ifs
    ;;
esac

run_step() {
  name=$1
  shift
  log="$EVIDENCE_DIR/${name}.log"
  printf '\n[VERIFY] %s\n' "$name"
  "$@" >"$log" 2>&1
  status=$?
  cat "$log"
  printf '%s\t%s\t%s\n' "$name" "$status" "${log#$ROOT_DIR/}" >>"$RECORDS"
  if [ "$status" -ne 0 ]; then OVERALL=1; fi
  return 0
}

configure_testcontainers() {
  command -v docker >/dev/null 2>&1 || return 0
  if [ -z "${DOCKER_HOST:-}" ]; then
    detected_docker_host=$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)
    if [ -n "$detected_docker_host" ]; then
      DOCKER_HOST=$detected_docker_host
      export DOCKER_HOST
    fi
  fi
  case "${DOCKER_HOST:-}" in
    *colima*.sock)
      : "${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:=/var/run/docker.sock}"
      : "${TESTCONTAINERS_HOST_OVERRIDE:=127.0.0.1}"
      export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE TESTCONTAINERS_HOST_OVERRIDE
      ;;
  esac
  local_non_proxy='-Dhttp.nonProxyHosts=localhost|127.*|[::1] -Dhttps.nonProxyHosts=localhost|127.*|[::1] -DsocksNonProxyHosts=localhost|127.*|[::1]'
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$local_non_proxy"
  export JAVA_TOOL_OPTIONS
}

for scope in $SCOPES; do
  case "$scope" in
    harness)
      run_step harness-state python3 "$ROOT_DIR/harness/harness.py" validate
      run_step harness-progress python3 "$ROOT_DIR/harness/harness.py" check-progress
      run_step harness-python-tests python3 -m unittest discover -s "$ROOT_DIR/harness/tests" -p 'test_*.py'
      run_step harness-shell-syntax sh -n "$ROOT_DIR/harness/init.sh" "$ROOT_DIR/harness/verify.sh" "$ROOT_DIR/harness/run-task.sh"
      ;;
    backend)
      run_step backend-tests sh -c "cd '$ROOT_DIR/backend' && mvn -pl hify-app -am test"
      ;;
    migration)
      configure_testcontainers
      docker_status=0
      docker info >"$EVIDENCE_DIR/migration-docker.log" 2>&1 || docker_status=$?
      cat "$EVIDENCE_DIR/migration-docker.log"
      printf '%s\t%s\t%s\n' migration-docker "$docker_status" "${EVIDENCE_DIR#$ROOT_DIR/}/migration-docker.log" >>"$RECORDS"
      if [ "$docker_status" -ne 0 ]; then
        OVERALL=1
      else
        run_step migration-postgres sh -c "cd '$ROOT_DIR/backend' && mvn -Dapi.version='${HIFY_DOCKER_API_VERSION:-1.44}' -pl hify-app -am -Dtest=AgentToolBindingMigrationTest,PostgresConcurrencyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test"
        run_step migration-not-skipped sh -c "grep -Eq 'Tests run: [1-9][0-9]*, Failures: 0, Errors: 0, Skipped: 0' '$ROOT_DIR/backend/hify-app/target/surefire-reports/com.hify.api.PostgresConcurrencyIntegrationTest.txt'"
      fi
      ;;
    runtime)
      run_step runtime-tests sh -c "cd '$ROOT_DIR/backend' && mvn -pl hify-app -am -Dtest=QueryLoopTest,PlanStateMachineTest,ExecutionContextStateTest,RunFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test"
      ;;
    eval)
      run_step intent-eval sh -c "cd '$ROOT_DIR/backend' && mvn -pl hify-chat -am -Dtest=IntentDecisionTest,DeterministicIntentRouterTest,LayeredIntentRouterTest,StructuredModelIntentClassifierTest,IntentEvaluationDatasetTest -Dsurefire.failIfNoSpecifiedTests=false test"
      ;;
    frontend)
      if [ ! -d "$ROOT_DIR/frontend/node_modules" ]; then
        run_step frontend-install sh -c "cd '$ROOT_DIR/frontend' && npm ci --no-audit --no-fund"
      fi
      run_step frontend-typecheck sh -c "cd '$ROOT_DIR/frontend' && npm run typecheck"
      run_step frontend-build sh -c "cd '$ROOT_DIR/frontend' && npm run build"
      ;;
  esac
done

HEAD_COMMIT=$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || printf unknown)
export HIFY_VERIFY_SCOPES=$(printf '%s' "$SCOPES" | sed 's/^ *//; s/  */,/g')
export HIFY_VERIFY_BASE=${BASE:-working-tree}
export HIFY_VERIFY_HEAD=$HEAD_COMMIT
export HIFY_VERIFY_STATUS=$OVERALL
export HIFY_VERIFY_RECORDS=$RECORDS
python3 - "$EVIDENCE_DIR/verification.json" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

steps = []
for line in Path(os.environ["HIFY_VERIFY_RECORDS"]).read_text(encoding="utf-8").splitlines():
    if not line:
        continue
    name, status, log = line.split("\t", 2)
    steps.append({"name": name, "exitCode": int(status), "log": log})
manifest = {
    "schemaVersion": 1,
    "kind": "verification",
    "scopes": os.environ["HIFY_VERIFY_SCOPES"].split(","),
    "base": os.environ["HIFY_VERIFY_BASE"],
    "headCommit": os.environ["HIFY_VERIFY_HEAD"],
    "result": "passed" if os.environ["HIFY_VERIFY_STATUS"] == "0" else "failed",
    "steps": steps,
    "finishedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
}
Path(sys.argv[1]).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

rm -f "$RECORDS"
printf '\n[VERIFY] result=%s evidence=%s\n' "$( [ "$OVERALL" -eq 0 ] && printf passed || printf failed )" "${EVIDENCE_DIR#$ROOT_DIR/}"
exit "$OVERALL"
