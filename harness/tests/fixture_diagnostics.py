"""Bounded diagnostics for isolated deployment fixtures, not production commands.

Keeps subprocess timeout/result semantics unchanged and records only a bounded
allowlisted description before TemporaryDirectory removes the fixture.
"""
import json
from pathlib import Path
import subprocess
import time


COMMANDS = frozenset({
    'id', 'stat', 'df', 'runuser', 'systemctl', 'curl', 'sha256sum',
    'pg_restore', 'nginx', 'sleep',
})


def fixture_snapshot(root):
    root = Path(root)
    state = 'unknown'
    try:
        with (root / 'service').open('r') as source:
            value = source.read(16).strip()
        if value in ('active', 'inactive'):
            state = value
    except OSError:
        pass
    stages = []
    truncated = False
    try:
        with (root / 'calls.jsonl').open('rb') as source:
            source.seek(0, 2)
            size = source.tell()
            truncated = size > 32768
            source.seek(max(0, size - 32768))
            lines = source.read(32768).splitlines()
        if truncated:
            lines = lines[1:]  # First line can be a partial JSON record.
        for line in lines[-16:]:
            try:
                record = json.loads(line)
            except (ValueError, UnicodeError):
                stages.append('unreadable')
                continue
            if not isinstance(record, list) or len(record) != 2:
                stages.append('unreadable')
                continue
            name, args = record
            if not isinstance(name, str) or name not in COMMANDS:
                stages.append('unknown')
                continue
            if (name == 'systemctl' and isinstance(args, list) and args
                    and args[0] in ('start', 'stop', 'is-active')):
                stages.append('systemctl.' + args[0])
            else:
                stages.append(name)
    except OSError:
        pass
    return {'fixtureService': state, 'lastStages': stages,
            'readTailTruncated': truncated}


def run_with_fixture_diagnostics(command, *, fixture_root, **kwargs):
    started = time.monotonic()
    try:
        return subprocess.run(command, **kwargs)
    except subprocess.TimeoutExpired as original:
        try:
            snapshot = fixture_snapshot(fixture_root)
            snapshot['elapsedSeconds'] = round(time.monotonic() - started, 3)
            original.add_note('Synthetic installer fixture: ' + json.dumps(snapshot))
        except Exception:
            # Best-effort diagnostics must never mask or replace the timeout.
            pass
        raise
