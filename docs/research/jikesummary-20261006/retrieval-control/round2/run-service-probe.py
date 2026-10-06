"""Compile current service + new tests in isolation; never mutate Maven outputs."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import argparse

parser=argparse.ArgumentParser()
parser.add_argument('--replacement',type=Path)
parser.add_argument('--select',default='com.hify.application.RunKnowledgeControlTest,com.hify.runtime.QueryLoopParentControlTest,com.hify.common.ExecutionControlTest,com.hify.knowledge.application.SemanticEmbeddingControlTest,com.hify.knowledge.application.KnowledgeRetrievalControlTest,com.hify.api.RetrievalEmbeddingHttpTest')
parser.add_argument('--count',default='27')
args=parser.parse_args()

scratch = Path('/tmp/hify-course-20261006.hhEhwt')
main = Path('/Users/weberzhao/hify')
work = scratch / 'retrieval-worktree'
jdk = Path('/Users/weberzhao/software/jdk-17.0.19.jdk/Contents/Home/bin')
report = main / 'backend/hify-chat/target/surefire-reports/TEST-com.hify.application.RunWorkflowControlTest.xml'
properties = {p.get('name'): p.get('value') for p in ET.parse(report).findall('properties/property')}
entries = [p for p in properties['java.class.path'].split(os.pathsep) if '/target/test-classes' not in p]
entries.append('/Users/weberzhao/.m2/repository/org/junit/platform/junit-platform-launcher/1.11.4/junit-platform-launcher-1.11.4.jar')
if not all(Path(p).exists() for p in entries):
    raise SystemExit('missing classpath entry')
out = Path(tempfile.mkdtemp(prefix='service-probe-', dir=scratch))
cp = os.pathsep.join(entries)
sources = [work / 'backend/hify-chat/src/main/java/com/hify/application/RunApplicationService.java',
           work / 'backend/hify-chat/src/main/java/com/hify/runtime/QueryLoop.java',
           work / 'backend/hify-common/src/main/java/com/hify/common/ExecutionControl.java',
           work / 'backend/hify-common/src/main/java/com/hify/common/ExecutionTimedOutException.java',
           work / 'backend/hify-knowledge/src/main/java/com/hify/knowledge/api/KnowledgeRetrievalPort.java',
           work / 'backend/hify-knowledge/src/main/java/com/hify/knowledge/application/SemanticEmbeddings.java',
           work / 'backend/hify-knowledge/src/main/java/com/hify/knowledge/application/KnowledgeRetrievalService.java',
           work / 'backend/hify-chat/src/test/java/com/hify/application/RunKnowledgeControlTest.java',
           work / 'backend/hify-chat/src/test/java/com/hify/runtime/QueryLoopParentControlTest.java',
           work / 'backend/hify-common/src/test/java/com/hify/common/ExecutionControlTest.java',
           work / 'backend/hify-knowledge/src/test/java/com/hify/knowledge/application/SemanticEmbeddingControlTest.java',
           work / 'backend/hify-knowledge/src/test/java/com/hify/knowledge/application/KnowledgeRetrievalControlTest.java',
           work / 'backend/hify-app/src/test/java/com/hify/api/RetrievalEmbeddingHttpTest.java',
           scratch / 'LightweightTest.java']
if args.replacement:
    matches=[i for i,p in enumerate(sources) if p.name==args.replacement.name]
    if len(matches)!=1: raise SystemExit('replacement must match exactly one source filename')
    sources[matches[0]]=args.replacement
print('worktreeHead=' + subprocess.check_output(['git','rev-parse','HEAD'],cwd=work,text=True).strip(), flush=True)
print('dependencyWorktreeHead=' + subprocess.check_output(['git','rev-parse','HEAD'],cwd=main,text=True).strip(), flush=True)
print('isolatedOutput=' + str(out), flush=True)
for source in sources:
    print('sourceSha256=' + hashlib.sha256(source.read_bytes()).hexdigest() + ' ' + source.name, flush=True)
subprocess.run([str(jdk/'javac'),'-J-Xmx128m','-cp',cp,'-d',str(out),*map(str,sources)],check=True)
agent = next(p for p in entries if '/byte-buddy-agent/' in p and p.endswith('.jar'))
result = subprocess.run([str(jdk/'java'),'-Xmx128m','-XX:ReservedCodeCacheSize=48m',
    '-javaagent:'+agent,'-cp',str(out)+os.pathsep+cp,'LightweightTest',
    args.select,args.count])
raise SystemExit(result.returncode)
