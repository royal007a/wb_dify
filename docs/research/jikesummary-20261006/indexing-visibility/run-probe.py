"""Diagnostic only: compile the production indexer with previously built dependencies."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--source-root', type=Path, required=True)
parser.add_argument('--classpath-report', type=Path, required=True)
parser.add_argument('--jdk-bin', type=Path, required=True)
args = parser.parse_args()
properties = {p.get('name'): p.get('value') for p in ET.parse(args.classpath_report).findall('properties/property')}
entries = [p for p in properties['java.class.path'].split(os.pathsep) if '/target/test-classes' not in p]
if not all(Path(p).exists() for p in entries):
    raise SystemExit('missing dependency; this script does not build Maven artifacts')
source = args.source_root / 'backend/hify-knowledge/src/main/java/com/hify/knowledge/application/DocumentIndexingService.java'
probe = Path(__file__).resolve().with_name('IndexFailureVisibilityProbe.java')
print('sourceHead=' + subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=args.source_root, text=True).strip(), flush=True)
for item in (source, probe, args.classpath_report):
    print('sha256=' + hashlib.sha256(item.read_bytes()).hexdigest() + ' ' + item.name, flush=True)
classpath = os.pathsep.join(entries)
agent = next(p for p in entries if '/byte-buddy-agent/' in p and p.endswith('.jar'))
with tempfile.TemporaryDirectory(prefix='hify-index-visibility-') as output:
    subprocess.run([str(args.jdk_bin / 'javac'), '-J-Xmx128m', '-cp', classpath, '-d', output,
                    str(source), str(probe)], check=True)
    result = subprocess.run([str(args.jdk_bin / 'java'), '-Xmx128m', '-XX:ReservedCodeCacheSize=48m',
                             '-javaagent:' + agent, '-cp', output + os.pathsep + classpath,
                             'com.hify.knowledge.application.IndexFailureVisibilityProbe'])
    print('exit=' + str(result.returncode), flush=True)
    raise SystemExit(result.returncode)
