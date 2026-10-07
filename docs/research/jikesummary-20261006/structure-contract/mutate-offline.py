#!/usr/bin/env python3
"""Mechanical mutations of a fixed Git archive, never the development worktree."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--commit', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[4]
sha = subprocess.check_output(['git', 'rev-parse', args.commit+'^{commit}'], cwd=repo, text=True).strip()
args.output.mkdir(parents=True, exist_ok=False)
archive = subprocess.check_output(['git', 'archive', sha], cwd=repo)
scratch = Path(tempfile.mkdtemp(prefix='hify-structure-archive-'))
with tarfile.open(fileobj=io.BytesIO(archive)) as packed:
    packed.extractall(scratch, filter='data')
helper = scratch/'backend/hify-app/src/test/java/com/hify/api/ModuleStructureContract.java'
original = helper.read_text()
cases = [
    ('M1-root', 'module.roots().stream().anyMatch(root->pkg.equals(root)||pkg.startsWith(root+"."))',
     'true', 'newlyIntroducedRootFails'),
    ('M2-split', 'SPLIT_OWNERS.getOrDefault(entry.getKey(),Set.of()).containsAll(entry.getValue())',
     'true', 'newSplitSubpackageFailsEvenInsideAllowedRoots'),
    ('M3-common-dependency', 'dependencies.get("hify-common").isEmpty()',
     'true', 'commonCannotAcquireBusinessDependency'),
    ('M4-reverse-dependency', '!dependencies.get(module).contains("hify-chat")',
     'true', 'providerCannotDependOnChat'),
    ('M5-port', 'if(module.port()!=null) requireType(source,types,module.port(),Tree.Kind.INTERFACE,false);',
     '// mutation: omit required port', 'missingPortIsRejected'),
    ('M6-marker', 'requireType(source,types,marker,Tree.Kind.CLASS,true);',
     '// mutation: omit required marker', 'missingMarkerCannotBeReplacedByAnEmptyDirectory'),
]
results = []

def run(name, expected_failure=None):
    p = subprocess.run(['sh', str(scratch/'docs/research/jikesummary-20261006/structure-contract/run-offline.sh')],
                       cwd=scratch, capture_output=True, text=True, timeout=90, env=os.environ.copy())
    content = p.stdout+p.stderr
    log = args.output/(name+'.log')
    log.write_text(content)
    if expected_failure is None:
        passed = p.returncode == 0 and '22 tests successful' in content
    else:
        # JUnit must actually discover all tests and show the intended assertion failure.
        passed = (p.returncode != 0 and '22 tests found' in content
                  and expected_failure in content
                  and ('AssertionError' in content or 'AssertionFailedError' in content)
                  and re.search(r'\[\s*[1-9][0-9]* tests failed', content) is not None)
    results.append({'case': name, 'exitCode': p.returncode, 'expectedMethod': expected_failure,
                    'expectationMet': passed, 'sha256': hashlib.sha256(log.read_bytes()).hexdigest()})
    print(name, 'expected outcome' if passed else 'UNEXPECTED', 'exit', p.returncode, flush=True)
    if not passed:
        raise AssertionError('Unexpected result; inspect '+str(log))

try:
    run('clean-archive')
    for name, before, after, method in cases:
        if original.count(before) != 1:
            raise AssertionError('Mutation must match exactly once: '+name)
        helper.write_text(original.replace(before, after))
        run(name, method)
        helper.write_text(original)
finally:
    helper.write_text(original)
    (args.output/'summary.json').write_text(json.dumps({
        'sourceCommit': sha, 'archiveSha256': hashlib.sha256(archive).hexdigest(),
        'scratchRetained': str(scratch), 'scope': 'Standalone JDK/JUnit structure tests only; no Maven, services or full gate',
        'results': results,
    }, ensure_ascii=False, indent=2)+'\n')
