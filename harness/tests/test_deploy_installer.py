import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[2]


class DeployInstallerTest(unittest.TestCase):
    def fixture(self, temp):
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
            self.assertEqual(len(checks), 2)
            self.assertTrue(all('workflow_runs' in sql for sql in checks))

    def test_late_running_agent_or_workflow_aborts_before_migration_and_restarts_old(self):
        for kind in ('agent', 'workflow'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                root, app, release, script, env = self.fixture(temp)
                env['DEPLOY_FIXTURE_LATE'] = kind
                result = subprocess.run(['sh', str(script), str(release)], env=env, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((root / 'service').read_text(), 'active')
                self.assertEqual((app / 'backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar').read_text(), 'old jar')
                self.assertFalse((release / 'database-before.dump').exists())
                self.assertFalse(any(name == 'runuser' and 'pg_dump' in args for name, args in self.calls(root)))

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
                    deadline = time.monotonic()+5
                    while not (root / 'waiting').exists() and process.poll() is None and time.monotonic() < deadline:
                        time.sleep(.01)
                    self.assertTrue((root / 'waiting').exists())
                    process.send_signal(sig)
                    (root / 'release-wait').touch()
                    stdout, stderr = process.communicate(timeout=5)
                    self.assertEqual(process.returncode, 128+sig, stderr.decode())
                    self.assertEqual((root / 'service').read_text(), 'inactive')
                    self.assertEqual(stderr.count(b'Upgrade halted.'), 1)
                    self.assertEqual(self.calls(root).count(['systemctl', ['stop', 'hify']]), 2)
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.communicate()


if __name__ == '__main__':
    unittest.main()
