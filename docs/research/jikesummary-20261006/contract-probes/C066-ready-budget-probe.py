"""Transcribe the displayed outer budget check, not the author's full executor.

One synthetic ready batch, all handlers succeed. No Hify, model, network or writes.
Observed overshoot is a counterexample, not a passing production safety test.
"""
import json


def replay(max_steps):
    ready = ['a', 'b', 'c']
    executed = 0
    calls = []
    while ready and executed < max_steps:
        for step in ready:
            calls.append(step)
            executed += 1
        ready = []
    return {'max_steps': max_steps, 'executed': executed, 'calls': calls}


if __name__ == '__main__':
    zero, one = replay(0), replay(1)
    assert zero == {'max_steps': 0, 'executed': 0, 'calls': []}
    assert one == {'max_steps': 1, 'executed': 3, 'calls': ['a', 'b', 'c']}
    print(json.dumps({'scope': 'C066 displayed ready-batch loop only; not Hify or author repository',
                      'observations': [zero, one]}, indent=2))
