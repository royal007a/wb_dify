"""Offline checks for the release admission boundary; never invoke SSH or installer."""
import ast
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'harness/context-rollout.py'


class ContextRolloutContractTest(unittest.TestCase):
    def test_optimized_python_still_rejects_incomplete_gate_before_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            harness = root/'harness'
            harness.mkdir()
            (harness/'context-rollout.py').write_bytes(SOURCE.read_bytes())
            (harness/'state.json').write_text(json.dumps({
                'currentTaskId':'CONTEXT-ROLLOUT-001', 'evidencePath':'evidence'}))
            (harness/'tasks.json').write_text(json.dumps({'tasks':[{
                'id':'OBS-CORRELATION-001', 'status':'blocked'}]}))
            result = subprocess.run([sys.executable, '-O', str(harness/'context-rollout.py')],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Current-source OBS gate must be completed', result.stderr)
            self.assertFalse((root/'evidence').exists())

    def test_release_admission_has_no_optimizable_assert_nodes(self):
        tree = ast.parse(SOURCE.read_text())
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(tree)))

    def test_optimized_python_rejects_invalid_gate_manifests(self):
        for invalid in ('result', 'schema', 'scopes', 'sha'):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                harness = root/'harness'
                harness.mkdir()
                (root/'gate').mkdir()
                (harness/'context-rollout.py').write_bytes(SOURCE.read_bytes())
                (harness/'state.json').write_text(json.dumps({
                    'currentTaskId':'CONTEXT-ROLLOUT-001', 'evidencePath':'evidence'}))
                gate = {'result':'passed', 'schemaVersion':3,
                        'scopes':['harness','migration','backend','runtime','eval','frontend']}
                if invalid == 'result': gate['result'] = 'failed'
                if invalid == 'schema': gate['schemaVersion'] = 2
                if invalid == 'scopes': gate['scopes'] = ['harness']
                data = json.dumps(gate).encode()
                (root/'gate/verification.json').write_bytes(data)
                digest = '0'*64 if invalid == 'sha' else hashlib.sha256(data).hexdigest()
                (harness/'tasks.json').write_text(json.dumps({'tasks':[{
                    'id':'OBS-CORRELATION-001', 'status':'completed',
                    'evidence':[{'path':'gate','verificationSha256':digest}]}]}))
                result = subprocess.run([sys.executable, '-O', str(harness/'context-rollout.py')],
                                        capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                expected = {'result':'Gate must pass schema 3', 'schema':'Gate must pass schema 3',
                            'scopes':'All six scopes required','sha':'Gate SHA mismatch'}[invalid]
                self.assertIn(expected, result.stderr)
                self.assertFalse((root/'evidence').exists())

    def running_probe(self):
        tree = ast.parse(SOURCE.read_text())
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Name):
                continue
            if node.func.id != 'command' or len(node.args) < 2:
                continue
            if not isinstance(node.args[1], ast.Constant) or node.args[1].value != 'remote-final':
                continue
            script = next(keyword.value.value for keyword in node.keywords if keyword.arg == 'text')
            self.assertNotIn('\0', script)
            program = script.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
            ast.parse(program)
            return program
        self.fail('Missing remote-final probe')

    def test_running_jar_probe_requires_descriptor_and_exact_command_target(self):
        program = self.running_probe()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root/'app.jar'
            target.write_bytes(b'known synthetic release')
            process = root/'proc/123'
            (process/'fd').mkdir(parents=True)
            (process/'cmdline').write_bytes(b'java\0-jar\0'+str(target).encode()+b'\0')
            # Isolated copy only. Production retains literal /proc and exact /opt/hify path.
            program = program.replace("'/proc/'", repr(str(root/'proc')+'/')).replace(
                '/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar', str(target))
            def run():
                return subprocess.run([sys.executable, '-c', program, '123'],
                                      text=True, capture_output=True)
            missing = run()
            self.assertNotEqual(missing.returncode, 0)
            self.assertIn('Cannot identify exactly one running jar SHA', missing.stderr)
            (process/'fd/9').symlink_to(target)
            good = run()
            self.assertEqual(good.returncode, 0, good.stderr)
            self.assertEqual(good.stdout.strip(), 'runningJarSha256='+hashlib.sha256(target.read_bytes()).hexdigest())
            (process/'cmdline').write_bytes(b'java\0-jar\0/other/app.jar\0')
            wrong = run()
            self.assertNotEqual(wrong.returncode, 0)
            self.assertIn('Running JVM command does not use expected jar', wrong.stderr)
