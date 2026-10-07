set -eu
systemctl is-active --quiet hify
pid=$(systemctl show hify -p MainPID --value)
test "$pid" -gt 0
python3 - "$pid" <<'PY'
import hashlib, os, sys
pid=sys.argv[1]
target='/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar'
def start_ticks():
    return int(open('/proc/'+pid+'/stat').read().rsplit(')', 1)[1].split()[19])
started=start_ticks()
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
with open(target,'rb') as f:
    if hashlib.file_digest(f,'sha256').hexdigest() not in digests:
        raise SystemExit('Running jar differs from installed jar')
if start_ticks()!=started:
    raise SystemExit('Process changed during identity probe')
print('runningPid='+pid)
print('runningStartTicks='+str(started))
print('runningJarSha256='+next(iter(digests)))
PY
df -Pk /opt/hify
