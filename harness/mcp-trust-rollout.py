#!/usr/bin/env python3
"""Invoke only the approved isolated Hify trust configuration task."""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
state = json.loads((root/'harness/state.json').read_text())
if state.get('currentTaskId') != 'MCP-TRUST-001':
    raise SystemExit('MCP-TRUST-001 must be active via run-task.sh')
evidence = root/state['evidencePath']
host = 'root@118.196.123.132'
work = '/opt/hify/releases/mcp-trust-20261006'
unit = 'hify-mcp-trust-20261006'
ssh = ['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10',host]
# Remote staging contains only reviewed source, no credential or business data.
subprocess.run([*ssh,'/bin/sh'],input='set -eu\ntest ! -e '+work+'\ntest "$(systemctl show '+unit+' -p LoadState --value)" = not-found\ninstall -d -m 700 '+work+'\n',
               text=True,check=True,timeout=30)
subprocess.run(['scp','-q',str(root/'harness/TrustProbe.java'),str(root/'harness/mcp-trust-remote.py'),
                host+':'+work+'/'],check=True,timeout=30)
script = '''set -eu
systemd-run --quiet --unit=UNIT --property=Type=oneshot --property=RemainAfterExit=yes /usr/bin/python3 WORK/mcp-trust-remote.py
for attempt in $(seq 1 720); do
  active=$(systemctl show UNIT -p ActiveState --value)
  sub=$(systemctl show UNIT -p SubState --value)
  if [ "$active" = failed ]; then
    test ! -f WORK/result.json || cat WORK/result.json
    exit 1
  fi
  if [ "$active" = active ] && [ "$sub" = exited ]; then
    cat WORK/result.json
    test "$(systemctl show UNIT -p Result --value)" = success
    exit 0
  fi
  sleep 1
done
printf 'Wait expired; inspect existing UNIT, do not repeat configuration.\\n' >&2
exit 1
'''.replace('UNIT',unit).replace('WORK',work)
result = subprocess.run([*ssh,'/bin/sh'],input=script,capture_output=True,text=True,timeout=800)
(evidence/'remote-trust.log').write_text(result.stdout+'\n'+result.stderr)
if result.stdout.strip():
    try:
        value = json.loads(result.stdout)
        (evidence/'trust-result.json').write_text(json.dumps(value,indent=2)+'\n')
    except json.JSONDecodeError:
        pass
if result.returncode:
    raise SystemExit('Trust configuration failed; inspect scoped result/rollback before retry')
if not (evidence/'trust-result.json').exists():
    raise SystemExit('Missing structured remote observations')
print('Hify independent truststore installed; certificate and hostname checks retained. Authenticated MCP discovery not performed.')
