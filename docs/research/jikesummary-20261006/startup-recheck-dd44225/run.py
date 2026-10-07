import json
import os
from pathlib import Path
import subprocess
import time

out = Path(__file__).parent
root = Path('/private/tmp/hify-course-20261006.hhEhwt/retrieval-worktree')
jdk = Path('/Users/weberzhao/software/jdk-17.0.19.jdk/Contents/Home')
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
if head != 'dd44225' and not head.startswith('dd44225'):
    raise RuntimeError('Unexpected source revision')
if subprocess.check_output(['git', 'status', '--porcelain'], cwd=root):
    raise RuntimeError('Working tree not clean')
env = os.environ.copy()
env['JAVA_HOME'] = str(jdk)
env['PATH'] = str(jdk / 'bin') + ':' + env['PATH']
env['MAVEN_OPTS'] = '-Xmx256m -XX:ReservedCodeCacheSize=96m'
env['JAVA_TOOL_OPTIONS'] = ('-Xmx768m -XX:ReservedCodeCacheSize=128m '
    '-Dspring.test.context.cache.maxSize=2 -Dhify.test.startup-diagnostics=true '
    f'-Xlog:gc*,safepoint:file={out}/gc-%p.log:time,uptime,level,tags')
tests = ('RunShutdownIntegrationTest#interruptedModelIsRecoverableAfterActualApplicationRestart+'
         'expiredInterruptedModelDoesNotRestartItsBudgetAfterActualApplicationRestart,'
         'ShutdownStartupDiagnosticsTest,MavenStructureTest,ModuleStructureContractTest')
cmd = ['/opt/homebrew/bin/mvn', '-B', '-o', '-pl', 'hify-app', '-am', '-Dtest=' + tests,
       '-Dsurefire.failIfNoSpecifiedTests=false', '-Dhify.test.reportsDirectory=' + str(out / 'reports'), 'test']
start = time.monotonic()
(out / 'source.json').write_text(json.dumps({'head': head, 'command': cmd, 'sourceClean': True}, indent=2))
with (out / 'vm-before.log').open('w') as log:
    subprocess.run(['vm_stat'], stdout=log, stderr=subprocess.STDOUT)
seen = {}
captured = set()
with (out / 'maven.log').open('w') as log:
    proc = subprocess.Popen(cmd, cwd=root / 'backend', env=env, stdout=log, stderr=subprocess.STDOUT)
    print('maven_pid=' + str(proc.pid), flush=True)
    while proc.poll() is None:
        now = time.monotonic()
        rows = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,command='], text=True)
        processes = {}
        for row in rows.splitlines():
            parts = row.split(None, 2)
            if len(parts) == 3:
                processes[int(parts[0])] = (int(parts[1]), parts[2])
        descendants = {proc.pid}
        for _ in range(10):
            new = {pid for pid, (parent, _) in processes.items() if parent in descendants}
            if new <= descendants:
                break
            descendants |= new
        for pid in descendants:
            command = processes.get(pid, (0, ''))[1]
            if not command.startswith(str(jdk / 'bin/java') + ' ') or 'surefirebooter' not in command:
                continue
            seen.setdefault(pid, now)
            for threshold in (40, 55):
                if now - seen[pid] >= threshold and (pid, threshold) not in captured:
                    captured.add((pid, threshold))
                    with (out / f'threads-{pid}-{threshold}s.log').open('w') as dump:
                        try:
                            result = subprocess.run([str(jdk / 'bin/jcmd'), str(pid), 'Thread.print'],
                                stdout=dump, stderr=subprocess.STDOUT, timeout=10)
                            print(f'thread_dump pid={pid} threshold={threshold} exit={result.returncode}', flush=True)
                        except subprocess.TimeoutExpired:
                            print(f'thread_dump pid={pid} threshold={threshold} timeout', flush=True)
        time.sleep(1)
    code = proc.wait()
with (out / 'vm-after.log').open('w') as log:
    subprocess.run(['vm_stat'], stdout=log, stderr=subprocess.STDOUT)
result = {'head': head, 'exit': code, 'elapsedSeconds': time.monotonic()-start,
          'testJvmPids': sorted(seen), 'threadDumpsAttempted': sorted(captured)}
(out / 'result.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result), flush=True)
raise SystemExit(code)
