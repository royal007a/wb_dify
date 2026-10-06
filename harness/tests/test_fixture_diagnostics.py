import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import fixture_diagnostics as candidate


class FixtureDiagnosticTest(unittest.TestCase):
    def test_preserves_same_timeout_and_original_ten_second_limit(self):
        error = subprocess.TimeoutExpired(['dash', 'synthetic'], 10)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'service').write_text('inactive')
            (root / 'calls.jsonl').write_text(json.dumps(['systemctl', ['stop', 'hify']]))
            with patch.object(candidate.subprocess, 'run', side_effect=error) as run:
                with self.assertRaises(subprocess.TimeoutExpired) as raised:
                    candidate.run_with_fixture_diagnostics(['dash', 'synthetic'],
                                                           fixture_root=root, timeout=10)
            self.assertIs(raised.exception, error)
            self.assertEqual(run.call_args.kwargs['timeout'], 10)
            self.assertTrue(getattr(error, '__notes__', []), 'timeout needs fixture diagnostics')
            self.assertIn('systemctl.stop', error.__notes__[0])
            self.assertIn('inactive', error.__notes__[0])

    def test_success_and_nonzero_result_objects_remain_unchanged(self):
        for code in (0, 1):
            expected = subprocess.CompletedProcess(['synthetic'], code, b'out', b'err')
            with patch.object(candidate.subprocess, 'run', return_value=expected):
                with patch.object(candidate, 'fixture_snapshot') as snapshot:
                    actual = candidate.run_with_fixture_diagnostics(['synthetic'],
                                                                   fixture_root='unused', timeout=10)
                    self.assertIs(actual, expected)
                    snapshot.assert_not_called()

    def test_diagnostic_failure_cannot_replace_original_timeout(self):
        error = subprocess.TimeoutExpired(['synthetic'], 10)
        with patch.object(candidate.subprocess, 'run', side_effect=error):
            with patch.object(candidate, 'fixture_snapshot', side_effect=RuntimeError('diagnostic failed')):
                with self.assertRaises(subprocess.TimeoutExpired) as raised:
                    candidate.run_with_fixture_diagnostics(['synthetic'],
                                                           fixture_root='unused', timeout=10)
        self.assertIs(raised.exception, error)

    def test_non_timeout_errors_are_not_reclassified(self):
        error = FileNotFoundError('synthetic missing shell')
        with patch.object(candidate.subprocess, 'run', side_effect=error):
            with self.assertRaises(FileNotFoundError) as raised:
                candidate.run_with_fixture_diagnostics(['synthetic'], fixture_root='unused')
        self.assertIs(raised.exception, error)

    def test_does_not_log_arguments_unknown_names_or_untrusted_service_text(self):
        marker = 'DO_NOT_LOG_SYNTHETIC_CREDENTIAL'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'service').write_text(marker)
            records = [['curl', [marker]], [marker, []], ['systemctl', ['stop', marker]]]
            (root / 'calls.jsonl').write_text('\n'.join(map(json.dumps, records)))
            result = candidate.fixture_snapshot(root)
        self.assertNotIn(marker, json.dumps(result))
        self.assertEqual(result['fixtureService'], 'unknown')
        self.assertEqual(result['lastStages'], ['curl', 'unknown', 'systemctl.stop'])

    def test_missing_files_and_partial_tail_are_bounded(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.assertEqual(candidate.fixture_snapshot(root)['lastStages'], [])
            (root / 'calls.jsonl').write_text('x' * 40000 + '\n' +
                                            '\n'.join(json.dumps(['curl', []]) for _ in range(30)))
            result = candidate.fixture_snapshot(root)
        self.assertTrue(result['readTailTruncated'])
        self.assertEqual(result['lastStages'], ['curl'] * 16)


if __name__ == '__main__':
    unittest.main()
