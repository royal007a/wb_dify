import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]


class DeployInstallerTest(unittest.TestCase):
    def fixture(self, temp, jar_entries=None):
        root = Path(temp)
        app = root / 'opt/hify'
        release = app / 'releases/spec-verify-test'
        release.mkdir(parents=True)
        files = {
            'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar': 'old jar',
            'frontend/dist/index.html': 'old index', 'frontend/dist/assets/old.js': 'old asset',
        }
        for path, value in files.items():
            target = app / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(value)
        for path in ('nginx/snippets/hify-path.conf', 'hify/mcp-credentials.env',
                     'systemd/system/hify.service.d/20-mcp-credentials.conf'):
            target = root / 'etc' / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('synthetic file, no real credential')
        package = root / 'package'
        for path, value in {
            'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar': 'new jar',
            'frontend/dist/index.html': 'new index', 'frontend/dist/assets/new.js': 'new asset',
            'deploy/nginx-path.conf': 'new snippet'
        }.items():
            target = package / path
            target.parent.mkdir(parents=True, exist_ok=True)
            if path.endswith('.jar'):
                if jar_entries == 'broken':
                    target.write_bytes(b'broken jar')
                else:
                    entries = jar_entries if jar_entries is not None else [
                        (f'BOOT-INF/classes/db/migration/V{i}__fixture.'+('class' if i == 8 else 'sql'), b'fixture')
                        for i in range(1,25)]
                    with zipfile.ZipFile(target, 'w') as archive:
                        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
                        for name, data in entries:
                            archive.writestr(name, data)
                    (root / 'new.jar').write_bytes(target.read_bytes())
            else:
                target.write_text(value)
        with tarfile.open(release / 'release.tar.gz', 'w:gz') as archive:
            for target in package.rglob('*'):
                if target.is_file():
                    archive.add(target, arcname=str(target.relative_to(package)))
        (release / 'release.sha256').write_text('synthetic checksum checked by fake command')
        script = root / 'install.sh'
        # Test-only transformation of a copy; production has no root override switch.
        script.write_text((ROOT / 'deploy/install-spec-release.sh').read_text()
                          .replace('/opt/hify', str(app)).replace('/etc/', str(root / 'etc')+'/'))
        fake_bin = root / 'bin'
        fake_bin.mkdir()
        source = (ROOT / 'harness/tests/fixtures/deploy_command.py').read_text().split('\n', 1)[1]
        for name in ('id', 'stat', 'df', 'runuser', 'systemctl', 'curl', 'sha256sum', 'pg_restore', 'nginx', 'sleep'):
            command = fake_bin / name
            command.write_text('#!'+sys.executable+'\n'+source)
            command.chmod(0o700)
        (root / 'service').write_text('active')
        env = dict(os.environ, PATH=str(fake_bin)+os.pathsep+os.environ['PATH'],
                   DEPLOY_FIXTURE_ROOT=str(root), HIFY_DEPLOY_HEALTH_ATTEMPTS='2')
        return root, app, release, script, env

    def calls(self, root):
        return [json.loads(line) for line in (root / 'calls.jsonl').read_text().splitlines()]

    def shells(self):
        return ['sh'] + (['dash'] if shutil.which('dash') else [])

    def wait_for_barrier(self, root, process):
        # Starting each fake Python command can take >5s in total on a busy Mac.
        # This is a fixture startup budget, not a production latency assertion.
        deadline = time.monotonic()+30
        while not (root / 'waiting').exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(.01)
        calls = self.calls(root) if (root / 'calls.jsonl').exists() else []
        self.assertTrue((root / 'waiting').exists(),
                        f'Fixture barrier not reached; exit={process.poll()}, calls={calls}')

    def test_closed_stderr_cannot_skip_recovery_or_replace_original_exit(self):
        for shell in self.shells():
            for stage in ('late', 'health'):
                with self.subTest(shell=shell, stage=stage), tempfile.TemporaryDirectory() as temp:
                    root, app, release, script, env = self.fixture(temp)
                    if stage == 'late':
                        env['DEPLOY_FIXTURE_LATE'] = 'agent'
                    else:
                        env['DEPLOY_FIXTURE_CURL_EXIT'] = '7'
                    result = subprocess.run(
                        [shell, '-c', 'exec 2>&-; exec "$@"', 'closed-stderr', shell, str(script), str(release)],
                        env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    self.assertEqual(result.returncode, 1)
                    self.assertEqual((root / 'service').read_text(), 'active' if stage == 'late' else 'inactive')
                    calls = self.calls(root)
                    self.assertIn(['systemctl', ['start', 'hify']], calls)
                    self.assertEqual(calls.count(['systemctl', ['stop', 'hify']]), 1 if stage == 'late' else 2)
                    self.assertEqual((app / 'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar').read_bytes(),
                                     b'old jar' if stage == 'late' else (root / 'new.jar').read_bytes())

    def test_invalid_preflight_never_stops_service(self):
        for shell in self.shells():
            for missing in ('0', '000', '301', 'jar', 'index', 'snippet', 'key'):
                with self.subTest(shell=shell, missing=missing), tempfile.TemporaryDirectory() as temp:
                    root, app, release, script, env = self.fixture(temp)
                    paths = {'jar': app / 'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar',
                             'index': app / 'frontend/dist/index.html',
                             'snippet': root / 'etc/nginx/snippets/hify-path.conf',
                             'key': root / 'etc/hify/mcp-credentials.env'}
                    if missing in paths:
                        paths[missing].unlink()  # Only a known synthetic fixture file.
                    else:
                        env['HIFY_DEPLOY_HEALTH_ATTEMPTS'] = missing
                    result = subprocess.run([shell, str(script), str(release)], env=env, capture_output=True)
                    self.assertNotEqual(result.returncode, 0)
                    calls = self.calls(root) if (root / 'calls.jsonl').exists() else []
                    self.assertFalse(any(name == 'systemctl' for name, args in calls), calls)
                    self.assertEqual((root / 'service').read_text(), 'active')
                    self.assertFalse((release / 'database-before.dump').exists())

    def test_broken_stderr_pipe_preserves_cleanup_and_original_exit(self):
        for shell in self.shells():
            for stage in ('late', 'health'):
                with self.subTest(shell=shell, stage=stage), tempfile.TemporaryDirectory() as temp:
                    root, app, release, script, env = self.fixture(temp)
                    env['DEPLOY_FIXTURE_LATE' if stage == 'late' else 'DEPLOY_FIXTURE_CURL_EXIT'] = 'agent' if stage == 'late' else '7'
                    read_fd, write_fd = os.pipe()
                    os.close(read_fd)  # A real pipe with no reader: EPIPE, not EBADF.
                    try:
                        result = subprocess.run([shell, str(script), str(release)], env=env,
                                                stdout=subprocess.PIPE, stderr=write_fd, timeout=10)
                    finally:
                        os.close(write_fd)
                    self.assertEqual(result.returncode, 1)
                    self.assertEqual((root / 'service').read_text(), 'active' if stage == 'late' else 'inactive')
                    self.assertEqual(self.calls(root).count(['systemctl', ['stop', 'hify']]), 1 if stage == 'late' else 2)

    def test_broken_stdout_does_not_roll_back_success_and_quiet_still_checks_state(self):
        for shell in self.shells():
            with self.subTest(shell=shell), tempfile.TemporaryDirectory() as temp:
                root, app, release, script, env = self.fixture(temp)
                read_fd, write_fd = os.pipe()
                os.close(read_fd)
                try:
                    result = subprocess.run([shell, str(script), str(release)], env=env,
                                            stdout=write_fd, stderr=subprocess.PIPE, timeout=10)
                finally:
                    os.close(write_fd)
                # Read-only diagnostics may fail after success; service must stay healthy.
                self.assertEqual((root / 'service').read_text(), 'active', result.stderr)
                self.assertEqual((root / 'etc/nginx/snippets/hify-path.conf').read_text(), 'new snippet')
                self.assertEqual((app / 'frontend/dist/index.html').read_text(), 'new index')
                self.assertIn(['systemctl', ['is-active', '--quiet', 'hify']], self.calls(root))
                self.assertEqual(self.calls(root).count(['systemctl', ['stop', 'hify']]), 1)
            with self.subTest(shell=shell, inactive=True), tempfile.TemporaryDirectory() as temp:
                root, app, release, script, env = self.fixture(temp)
                env['DEPLOY_FIXTURE_FINAL_INACTIVE'] = '1'
                result = subprocess.run([shell, str(script), str(release)], env=env, capture_output=True, timeout=10)
                self.assertEqual(result.returncode, 3)
                self.assertEqual((root / 'service').read_text(), 'inactive')
                self.assertEqual(self.calls(root).count(['systemctl', ['stop', 'hify']]), 2)

    def test_signal_after_success_does_not_stop_or_revert_healthy_release(self):
        for shell in self.shells():
            for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
                with self.subTest(shell=shell, sig=sig), tempfile.TemporaryDirectory() as temp:
                    root, app, release, script, env = self.fixture(temp)
                    env['DEPLOY_FIXTURE_FINAL_SHA_WAIT'] = '1'
                    process = subprocess.Popen([shell, str(script), str(release)], env=env,
                                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    try:
                        self.wait_for_barrier(root, process)
                        # The barrier is after actual health, key and static publication checks.
                        self.assertEqual((root / 'service').read_text(), 'active')
                        self.assertEqual((app / 'frontend/dist/index.html').read_text(), 'new index')
                        process.send_signal(sig)
                        (root / 'release-wait').touch()
                        stdout, stderr = process.communicate(timeout=10)
                        # After trap removal, shells differ: sh can finish normally
                        # when only the parent receives INT while its child succeeds.
                        # The contract is no rollback, not an enforced signal exit.
                        self.assertIn(process.returncode, (0, -sig, 128+sig))
                        self.assertEqual((root / 'service').read_text(), 'active')
                        self.assertEqual((root / 'etc/nginx/snippets/hify-path.conf').read_text(), 'new snippet')
                        self.assertEqual((app / 'frontend/dist/index.html').read_text(), 'new index')
                        self.assertEqual(self.calls(root).count(['systemctl', ['stop', 'hify']]), 1)
                        self.assertNotIn(b'Upgrade halted.', stderr)
                    finally:
                        (root / 'release-wait').touch()
                        if process.poll() is None:
                            process.kill()
                            process.communicate()

    def test_success_and_shell_syntax(self):
        subprocess.run(['sh', '-n', str(ROOT / 'deploy/install-spec-release.sh')], check=True)
        with tempfile.TemporaryDirectory() as temp:
            root, app, release, script, env = self.fixture(temp)
            result = subprocess.run(['sh', str(script), str(release)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((app / 'frontend/dist/index.html').read_text(), 'new index')
            self.assertEqual((release / 'previous-dist/index.html').read_text(), 'old index')
            calls = self.calls(root)
            stop = calls.index(['systemctl', ['stop', 'hify']])
            dump = next(i for i, (name, args) in enumerate(calls) if name == 'runuser' and 'pg_dump' in args)
            self.assertLess(stop, dump)
            checks = [args[-1] for name, args in calls if name == 'runuser' and 'count(*)' in args[-1]]
            self.assertEqual(len(checks), 6)
            for table in ('agent_runs', 'workflow_runs', 'document_index_tasks'):
                self.assertEqual(sum('FROM '+table in sql for sql in checks), 2)
            self.assertTrue(all("'PENDING','RUNNING'" in sql for sql in checks if 'document_index_tasks' in sql))

    def test_late_running_agent_or_workflow_aborts_before_migration_and_restarts_old(self):
        for kind in ('agent', 'workflow', 'index-pending', 'index-running', 'query-error'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                root, app, release, script, env = self.fixture(temp)
                env['DEPLOY_FIXTURE_LATE'] = kind
                result = subprocess.run(['sh', str(script), str(release)], env=env, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((root / 'service').read_text(), 'active')
                self.assertEqual((app / 'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar').read_text(), 'old jar')
                self.assertFalse((release / 'database-before.dump').exists())
                self.assertFalse(any(name == 'runuser' and 'pg_dump' in args for name, args in self.calls(root)))

    def test_artifact_migrations_are_checked_before_stop_and_bound_after_start(self):
        entries=[(f'BOOT-INF/classes/db/migration/V{i}__fixture.sql',b'select 1;') for i in range(1,25)]
        for shell in self.shells():
            for defect in ('broken', 'empty', 'gap', 'duplicate', 'bad-name', 'downgrade', 'failed-db'):
                with self.subTest(shell=shell, defect=defect), tempfile.TemporaryDirectory() as temp:
                    altered={'broken':'broken','empty':[], 'gap':entries[1:],
                             'duplicate':entries+[('BOOT-INF/classes/db/migration/V1__duplicate.sql',b'select 1;')],
                             'bad-name':entries+[('BOOT-INF/classes/db/migration/V025__bad.sql',b'select 1;')]}.get(defect,entries)
                    root, app, release, script, env=self.fixture(temp,altered)
                    if defect=='downgrade':env['DEPLOY_FIXTURE_SCHEMA_BEFORE']=','.join(map(str,range(1,26)))+'|true'
                    if defect=='failed-db':env['DEPLOY_FIXTURE_SCHEMA_BEFORE']='1,2|false'
                    result=subprocess.run([shell,str(script),str(release)],env=env,capture_output=True,timeout=30)
                    self.assertNotEqual(result.returncode,0,defect)
                    self.assertFalse(any(name=='systemctl' for name,args in self.calls(root)),self.calls(root))
                    self.assertEqual((root/'service').read_text(),'active')
                    self.assertFalse((release/'database-before.dump').exists())
            for applied in (23,24):
                with self.subTest(shell=shell,applied=applied), tempfile.TemporaryDirectory() as temp:
                    root,app,release,script,env=self.fixture(temp)
                    env['DEPLOY_FIXTURE_SCHEMA_AFTER']=','.join(map(str,range(1,applied+1)))+'|true'
                    result=subprocess.run([shell,str(script),str(release)],env=env,capture_output=True,timeout=30)
                    self.assertEqual(result.returncode,0 if applied==24 else 1,result.stderr)
                    self.assertEqual((root/'service').read_text(),'active' if applied==24 else 'inactive')
                    self.assertGreater((release/'database-before.dump').stat().st_size,0)
                    self.assertEqual((app/'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar').read_bytes(),(root/'new.jar').read_bytes())

    def test_health_failure_preserves_backup_and_stops_new_service_without_db_restore(self):
        with tempfile.TemporaryDirectory() as temp:
            root, app, release, script, env = self.fixture(temp)
            env['DEPLOY_FIXTURE_CURL_EXIT'] = '7'
            result = subprocess.run(['sh', str(script), str(release)], env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((root / 'service').read_text(), 'inactive')
            self.assertGreater((release / 'database-before.dump').stat().st_size, 0)
            restores = [args for name, args in self.calls(root) if name == 'pg_restore']
            self.assertEqual(restores, [['--list', str(release / 'database-before.dump')]])
            self.assertEqual(sum(name == 'curl' for name, args in self.calls(root)), 2)

    def test_catchable_signals_set_nonzero_exit_and_cleanup_once(self):
        for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
            with self.subTest(sig=sig), tempfile.TemporaryDirectory() as temp:
                root, app, release, script, env = self.fixture(temp)
                env['DEPLOY_FIXTURE_WAIT'] = '1'
                process = subprocess.Popen(['sh', str(script), str(release)], env=env,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    self.wait_for_barrier(root, process)
                    process.send_signal(sig)
                    (root / 'release-wait').touch()
                    stdout, stderr = process.communicate(timeout=10)
                    self.assertEqual(process.returncode, 128+sig, stderr.decode())
                    self.assertEqual((root / 'service').read_text(), 'inactive')
                    self.assertEqual(stderr.count(b'Upgrade halted.'), 1)
                    self.assertEqual(self.calls(root).count(['systemctl', ['stop', 'hify']]), 2)
                finally:
                    (root / 'release-wait').touch()
                    if process.poll() is None:
                        process.kill()
                        process.communicate()


if __name__ == '__main__':
    unittest.main()
