#!/usr/bin/env python3
"""Fake OS/network commands for an isolated installer copy. Never run real services."""
import json
import os
import signal
import re
from pathlib import Path
import sys
import time

root = Path(os.environ['DEPLOY_FIXTURE_ROOT'])
name, args = Path(sys.argv[0]).name, sys.argv[1:]
with (root / 'calls.jsonl').open('a') as log:
    log.write(json.dumps([name, args])+'\n')
state = root / 'service'
if name == 'id':
    print('0')
elif name == 'stat':
    print('600' if args[1] == '%a' else '1:20:3:600:root')
elif name == 'df':
    print('Filesystem 1024-blocks Used Available Capacity Mounted on\nfake 4000000 1000000 3000000 25% /')
elif name == 'systemctl':
    if args == ['stop', 'hify']:
        state.write_text('inactive')
        (root / 'stopped').touch()
    elif args == ['start', 'hify']:
        state.write_text('active')
    elif args in (['is-active', 'hify'], ['is-active', '--quiet', 'hify']):
        # Match systemctl's SIGPIPE, not Python's default ignored PIPE.
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
        if '--quiet' not in args:
            print(state.read_text(), flush=True)
        if '--quiet' in args and os.environ.get('DEPLOY_FIXTURE_FINAL_INACTIVE') == '1':
            sys.exit(3)
        sys.exit(0 if state.read_text() == 'active' else 3)
elif name == 'runuser':
    if 'pg_dump' in args:
        print('synthetic custom backup')
        sys.exit(int(os.environ.get('DEPLOY_FIXTURE_DUMP_EXIT', '0')))
    elif 'psql' in args:
        sql = args[-1]
        if 'flyway_schema_history' in sql:
            if 'string_agg' in sql:
                phase='AFTER' if (root/'stopped').exists() else 'BEFORE'
                default=','.join(map(str,range(1,25 if phase=='AFTER' else 24)))+'|true'
                print(os.environ.get('DEPLOY_FIXTURE_SCHEMA_'+phase,default))
            else:
                print('t' if 'bool_and' in sql else '21|t\n22|t\n23|t')
        else:
            match=re.search(r'FROM\s+(agent_runs|workflow_runs|document_index_tasks)\b',sql,re.I)
            assert match,sql
            table=match.group(1)
            late = os.environ.get('DEPLOY_FIXTURE_LATE', '')
            if (root / 'stopped').exists() and late:
                if late=='query-error':sys.exit(2)
                table_for={'agent':'agent_runs','workflow':'workflow_runs','index-pending':'document_index_tasks','index-running':'document_index_tasks'}
                selected=table_for[late]==table
                if late=='index-pending':selected=selected and "'PENDING'" in sql
                print('1' if selected else '0')
            else:
                print('0')
elif name == 'curl':
    if os.environ.get('DEPLOY_FIXTURE_WAIT') == '1':
        (root / 'waiting').touch()
        deadline = time.monotonic()+60
        while not (root / 'release-wait').exists() and time.monotonic() < deadline:
            time.sleep(.01)
    sys.exit(int(os.environ.get('DEPLOY_FIXTURE_CURL_EXIT', '0')))
elif name == 'sha256sum' and args[0] != '-c' and os.environ.get('DEPLOY_FIXTURE_FINAL_SHA_WAIT') == '1':
    (root / 'waiting').touch()
    deadline = time.monotonic()+60
    while not (root / 'release-wait').exists() and time.monotonic() < deadline:
        time.sleep(.01)
elif name == 'nginx':
    sys.exit(int(os.environ.get('DEPLOY_FIXTURE_NGINX_EXIT', '0')))
elif name == 'pg_restore':
    sys.exit(int(os.environ.get('DEPLOY_FIXTURE_RESTORE_EXIT', '0')))
elif name in ('sha256sum', 'sleep'):
    pass
else:
    raise AssertionError(name)
