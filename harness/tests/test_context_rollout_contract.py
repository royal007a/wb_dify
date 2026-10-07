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
        for invalid in ('result', 'schema', 'scopes', 'sha', 'head'):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                harness = root/'harness'
                harness.mkdir()
                (root/'gate').mkdir()
                (harness/'context-rollout.py').write_bytes(SOURCE.read_bytes())
                (harness/'state.json').write_text(json.dumps({
                    'currentTaskId':'CONTEXT-ROLLOUT-001', 'evidencePath':'evidence'}))
                gate = {'result':'passed', 'schemaVersion':3,
                        'scopes':['harness','migration','backend','runtime','eval','frontend'],
                        'headCommit':'a'*40}
                if invalid == 'result': gate['result'] = 'failed'
                if invalid == 'schema': gate['schemaVersion'] = 2
                if invalid == 'scopes': gate['scopes'] = ['harness']
                if invalid == 'head': gate['headCommit'] = 'a; touch marker'
                data = json.dumps(gate).encode()
                (root/'gate/verification.json').write_bytes(data)
                digest = '0'*64 if invalid == 'sha' else hashlib.sha256(data).hexdigest()
                (harness/'tasks.json').write_text(json.dumps({'tasks':[{
                    'id':'OBS-CORRELATION-001', 'status':'completed',
                    'evidence':[{'path':'gate','verificationSha256':digest,'headCommit':gate['headCommit']}]}]}))
                result = subprocess.run([sys.executable, '-O', str(harness/'context-rollout.py')],
                                        capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                expected = {'result':'Gate must pass schema 3', 'schema':'Gate must pass schema 3',
                            'scopes':'All six scopes required','sha':'Gate SHA mismatch',
                            'head':'Invalid gate head format'}[invalid]
                self.assertIn(expected, result.stderr)
                self.assertFalse((root/'evidence').exists())

    def running_probe(self):
        tree = ast.parse(SOURCE.read_text())
        assignment = next(node for node in tree.body if isinstance(node, ast.Assign)
                          and any(isinstance(target, ast.Name) and target.id == 'running_identity'
                                  for target in node.targets))
        script = ast.literal_eval(assignment.value)
        self.assertNotIn('\0', script)
        program = script.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        ast.parse(program)
        return program

    def test_same_identity_probe_runs_in_preflight_and_final(self):
        tree = ast.parse(SOURCE.read_text())
        preflight = next(node.value for node in tree.body if isinstance(node, ast.Assign)
                         and any(isinstance(target, ast.Name) and target.id == 'preflight'
                                 for target in node.targets))
        final = next(node for node in ast.walk(tree) if isinstance(node, ast.Call)
                     and isinstance(node.func, ast.Name) and node.func.id == 'command'
                     and len(node.args)>1 and isinstance(node.args[1], ast.Constant)
                     and node.args[1].value == 'remote-final')
        for expression in (preflight, next(k.value for k in final.keywords if k.arg=='text')):
            self.assertTrue(any(isinstance(n, ast.Name) and n.id=='running_identity'
                                for n in ast.walk(expression)))

    def test_invalid_output_bytes_do_not_mask_command_failure(self):
        tree = ast.parse(SOURCE.read_text())
        function = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name=='command')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            namespace = {'ROOT':root, 'evidence':root, 'subprocess':subprocess, 'json':json}
            exec(compile(ast.Module(body=[function], type_ignores=[]), str(SOURCE), 'exec'), namespace)
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                namespace['command']([sys.executable, '-c',
                    'import sys; sys.stdout.buffer.write(bytes([255])); sys.exit(7)'], 'remote-preflight')
            self.assertEqual(caught.exception.returncode, 7)
            self.assertEqual((root/'remote-preflight.log').read_bytes(), bytes([255]))
            self.assertIn('\ufffd', json.loads((root/'remote-observations.json').read_text())['observations']['remote-preflight'])

    def test_running_jar_probe_requires_descriptor_and_exact_command_target(self):
        program = self.running_probe()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root/'app.jar'
            target.write_bytes(b'known synthetic release')
            process = root/'proc/123'
            (process/'fd').mkdir(parents=True)
            (process/'cmdline').write_bytes(b'java\0-jar\0'+str(target).encode()+b'\0')
            (process/'stat').write_text('123 (java) '+' '.join(['S']+['0']*18+['567']))
            # Isolated copy only. Production retains literal /proc and exact /opt/hify path.
            self.assertIn("'/proc/'", program)
            self.assertIn('/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar', program)
            original = program
            program = program.replace("'/proc/'", repr(str(root/'proc')+'/')).replace(
                '/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar', str(target))
            self.assertNotEqual(program, original)
            self.assertNotIn("'/proc/'", program)
            self.assertNotIn('/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar', program)
            def run():
                return subprocess.run([sys.executable, '-c', program, '123'],
                                      text=True, capture_output=True)
            missing = run()
            self.assertNotEqual(missing.returncode, 0)
            self.assertIn('Cannot identify exactly one running jar SHA', missing.stderr)
            (process/'fd/9').symlink_to(target)
            good = run()
            self.assertEqual(good.returncode, 0, good.stderr)
            self.assertEqual(good.stdout.splitlines(), ['runningPid=123','runningStartTicks=567',
                'runningJarSha256='+hashlib.sha256(target.read_bytes()).hexdigest()])
            (process/'cmdline').write_bytes(b'java\0-jar\0/other/app.jar\0')
            wrong = run()
            self.assertNotEqual(wrong.returncode, 0)
            self.assertIn('Running JVM command does not use expected jar', wrong.stderr)
