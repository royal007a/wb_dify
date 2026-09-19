#!/bin/sh

set -u

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LOCK_DIR="$ROOT_DIR/harness/.run-lock"
APPROVAL_REF=""
STARTED=0
FINISHED=0
TASK_ID=""

usage() {
  cat <<'USAGE'
Usage: ./harness/run-task.sh [--approval-ref <message-or-ticket>] <TASK-ID> -- <command> [args...]

The task must exist in harness/tasks.json. Only one task can run at a time.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --approval-ref) APPROVAL_REF=${2:?missing approval reference}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    *)
      if [ -z "$TASK_ID" ]; then TASK_ID=$1; shift; else break; fi
      ;;
  esac
done

[ -n "$TASK_ID" ] || { usage >&2; exit 2; }
[ "$#" -gt 0 ] || { printf '[ERROR] task command is required after --\n' >&2; exit 2; }

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  printf '[ERROR] another harness task holds %s\n' "$LOCK_DIR" >&2
  exit 3
fi

cleanup() {
  status=$?
  if [ "$STARTED" -eq 1 ] && [ "$FINISHED" -eq 0 ]; then
    python3 "$ROOT_DIR/harness/harness.py" checkpoint "$TASK_ID" "runner exited unexpectedly with status $status" >/dev/null 2>&1 || true
    python3 "$ROOT_DIR/harness/harness.py" finish "$TASK_ID" blocked --exit-code "$status" --reason "runner exited unexpectedly" >/dev/null 2>&1 || true
    FINISHED=1
  fi
  rmdir "$LOCK_DIR" 2>/dev/null || true
}

interrupt() {
  status=130
  if [ "$STARTED" -eq 1 ] && [ "$FINISHED" -eq 0 ]; then
    python3 "$ROOT_DIR/harness/harness.py" checkpoint "$TASK_ID" "interrupted before completion" >/dev/null 2>&1 || true
    python3 "$ROOT_DIR/harness/harness.py" finish "$TASK_ID" blocked --exit-code "${status:-130}" --reason "task interrupted" >/dev/null 2>&1 || true
    FINISHED=1
  fi
  cleanup
  exit "${status:-130}"
}
trap interrupt HUP INT TERM
trap cleanup EXIT

if [ -n "$(git -C "$ROOT_DIR" status --porcelain --untracked-files=all)" ]; then
  printf '[ERROR] working tree must be clean before starting an atomic task\n' >&2
  exit 4
fi

if [ -n "$APPROVAL_REF" ]; then
  EVIDENCE_REL=$(python3 "$ROOT_DIR/harness/harness.py" start "$TASK_ID" --approval-ref "$APPROVAL_REF") || exit $?
else
  EVIDENCE_REL=$(python3 "$ROOT_DIR/harness/harness.py" start "$TASK_ID") || exit $?
fi
STARTED=1
EVIDENCE_DIR="$ROOT_DIR/$EVIDENCE_REL"
COMMAND_LOG="$EVIDENCE_DIR/command.log"

python3 "$ROOT_DIR/harness/harness.py" checkpoint "$TASK_ID" "task command started" >/dev/null
printf '[TASK] %s evidence=%s\n' "$TASK_ID" "$EVIDENCE_REL"

"$@" >"$COMMAND_LOG" 2>&1
COMMAND_STATUS=$?
cat "$COMMAND_LOG"
if [ "$COMMAND_STATUS" -ne 0 ]; then
  python3 "$ROOT_DIR/harness/harness.py" checkpoint "$TASK_ID" "task command failed with exit $COMMAND_STATUS" >/dev/null
  python3 "$ROOT_DIR/harness/harness.py" finish "$TASK_ID" blocked --exit-code "$COMMAND_STATUS" --reason "task command failed"
  FINISHED=1
  exit "$COMMAND_STATUS"
fi

python3 "$ROOT_DIR/harness/harness.py" checkpoint "$TASK_ID" "task command completed; verification starting" >/dev/null
VERIFY_SCOPES=$(python3 "$ROOT_DIR/harness/harness.py" verify-scopes "$TASK_ID") || exit $?
"$ROOT_DIR/harness/verify.sh" --scope "$VERIFY_SCOPES" --evidence-dir "$EVIDENCE_REL"
VERIFY_STATUS=$?
if [ "$VERIFY_STATUS" -ne 0 ]; then
  python3 "$ROOT_DIR/harness/harness.py" checkpoint "$TASK_ID" "verification failed with exit $VERIFY_STATUS" >/dev/null
  python3 "$ROOT_DIR/harness/harness.py" finish "$TASK_ID" blocked --exit-code "$VERIFY_STATUS" --reason "verification failed"
  FINISHED=1
  exit "$VERIFY_STATUS"
fi

python3 "$ROOT_DIR/harness/harness.py" finish "$TASK_ID" completed --exit-code 0
FINISHED=1
printf '[TASK] %s completed\n' "$TASK_ID"
