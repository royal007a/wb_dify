"""Narrow release guard checks, extracted from a fixed production controller.

No installer, network, Docker or project mutation. Run with controller path and
green|pid-mutant|tick-mutant|budget-mutant. Mutants alter isolated expressions.
"""
import ast
import hashlib
from pathlib import Path
import subprocess
import sys

source = Path(sys.argv[1])
mode = sys.argv[2]
if mode not in {'green', 'pid-mutant', 'tick-mutant', 'budget-mutant'}:
    raise SystemExit('unsupported mode')
raw = source.read_bytes()
tree = ast.parse(raw)
print('controllerSha256=' + hashlib.sha256(raw).hexdigest(), flush=True)


def guard(message, namespace):
    matches = [n for n in ast.walk(tree) if isinstance(n, ast.Call)
               and isinstance(n.func, ast.Name) and n.func.id == 'require'
               and len(n.args) == 2 and isinstance(n.args[1], ast.Constant)
               and n.args[1].value == message]
    assert len(matches) == 1, message
    expression = matches[0].args[0]
    if ((mode == 'pid-mutant' and message == 'Running PID was not replaced')
            or (mode == 'tick-mutant' and message == 'Running start time was not advanced')):
        expression = ast.Constant(value=True)
    expr = ast.fix_missing_locations(ast.Expression(expression))
    return bool(eval(compile(expr, str(source), 'eval'), namespace))


for before, after, accepted in [(['runningPid=1'], ['runningPid=2'], True),
                                (['runningPid=1'], ['runningPid=1'], False),
                                ([], ['runningPid=2'], False),
                                (['runningPid=1'], [], False),
                                (['runningPid=1', 'runningPid=3'], ['runningPid=2'], False)]:
    actual = guard('Running PID was not replaced', {'before_pids': before, 'after_pids': after})
    assert actual == accepted, ('PID guard', before, after, actual, accepted)
print('PID: distinct singleton accepted; same/missing/duplicate rejected', flush=True)

for before, after, accepted in [([10], [11], True), ([10], [10], False),
                                ([10], [9], False), ([], [11], False),
                                ([10], [], False), ([10], [11, 12], False)]:
    actual = guard('Running start time was not advanced', {'before_starts': before, 'after_starts': after})
    assert actual == accepted, ('start tick guard', before, after, actual, accepted)
print('ticks: later singleton accepted; same/earlier/missing/duplicate rejected', flush=True)

preflight = next(n.value for n in tree.body if isinstance(n, ast.Assign)
                 and any(isinstance(t, ast.Name) and t.id == 'preflight' for t in n.targets))
text = eval(compile(ast.Expression(preflight), str(source), 'eval'),
            {'release': '/unused', 'unit': 'unused', 'running_identity': '', 'staging_kib': 101})
formula = [line for line in text.splitlines() if line.startswith('required_kib=')]
comparison = [line for line in text.splitlines() if line == 'test "$free_kib" -ge "$required_kib"']
assert len(formula) == len(comparison) == 1
formula = formula[0]
assert formula.count('+ 2097152') == 1
if mode == 'budget-mutant':
    formula = formula.replace('+ 2097152', '+ 0')
# No remote commands are evaluated: only the extracted arithmetic and comparison.
expected = 101 + 120 + 2 + 2097152  # ceil(1025 bytes / 1024) is 2 KiB
for free, accepted in [(expected - 1, False), (expected, True), (expected + 1, True)]:
    script = (f'set -eu\nold_kib=120\ndb_bytes=1025\nfree_kib={free}\n'
              + formula + '\n' + comparison[0] + '\n')
    result = subprocess.run(['sh'], input=script, text=True, capture_output=True)
    assert (result.returncode == 0) == accepted, ('reserve guard', free, result.returncode, accepted)
print('budget: reserve + staging + old + ceil(database), -1/equal/+1 boundaries passed', flush=True)
