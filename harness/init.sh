#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

for command_name in git python3; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf '[ERROR] missing command: %s\n' "$command_name" >&2
    exit 1
  }
done

mkdir -p "$ROOT_DIR/harness/evidence" \
  "$ROOT_DIR/exec-plans/active" \
  "$ROOT_DIR/exec-plans/completed" \
  "$ROOT_DIR/exec-plans/blocked"

python3 "$ROOT_DIR/harness/harness.py" validate
python3 "$ROOT_DIR/harness/harness.py" render-progress >/dev/null
python3 "$ROOT_DIR/harness/harness.py" check-progress

printf '[OK] Harness initialized without modifying business data.\n'
