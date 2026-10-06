#!/usr/bin/env python3
"""One approved, certificate-only Hify configuration change. No credential reads."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

WORK = Path('/opt/hify/releases/mcp-trust-20261006')
TRUST = Path('/etc/hify-trust/workbench-20261006.jks')
DROP = Path('/etc/systemd/system/hify.service.d/40-workbench-trust.conf')
JAVA = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin/java')
CA = JAVA.parent.parent / 'lib/security/cacerts'
CERT = Path('/data/certs/self.crt')
JAR = Path('/opt/hify/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar')
PIN = 'e1bb50bd7d35af61eb0bd9f921f8fab8ad61c7b521c67034e875b22d0e491ab5'
OPTIONS = '-Djavax.net.ssl.trustStore='+str(TRUST)+' -Djavax.net.ssl.trustStoreType=JKS'
observed = {'scope':'Hify JVM trust only; no authenticated MCP calls or tool publication', 'steps':[]}

def require(ok, label):
    if not ok:
        raise RuntimeError(label)

def call(args, timeout=60):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    require(result.returncode == 0, 'Command failed: '+args[0]+' (output intentionally omitted)')
    return result.stdout.strip()

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def property(name):
    return call(['systemctl','show','hify','-p',name,'--value'])

def idle():
    sql = "SELECT (SELECT count(*) FROM agent_runs WHERE state='RUNNING')+(SELECT count(*) FROM workflow_runs WHERE status='RUNNING')+(SELECT count(*) FROM document_index_tasks WHERE state IN ('PENDING','RUNNING'))"
    require(call(['runuser','-u','postgres','--','psql','-d','hify','-Atqc',sql]) == '0', 'In-flight work; do not restart')

def metadata():
    return call(['stat','-c','%i:%s:%Y:%a:%U','/etc/hify/mcp-credentials.env'])

def health():
    deadline = time.monotonic()+120
    while time.monotonic() < deadline:
        result = subprocess.run(['curl','--noproxy','*','-fs','--max-time','2',
                                 'http://127.0.0.1:28080/api/v1/health'],capture_output=True)
        if result.returncode == 0 and property('ActiveState') == 'active':
            return True
        time.sleep(1)
    return False

def java_probe(*args, as_user=False):
    command = [str(JAVA),'-cp',str(TRUST.parent if as_user else WORK),'TrustProbe',*args]
    if as_user:
        command = ['runuser','-u','hifyapp','--',*command]
    value = json.loads(call(command, timeout=30))
    observed['steps'].append(value)
    return value

require(os.geteuid() == 0, 'Root required')
require(WORK.is_dir() and not (WORK/'result.json').exists(), 'Unique task directory required')
require(not DROP.exists() and not TRUST.parent.exists(), 'Dedicated configuration already exists; inspect before retry')
require(property('ActiveState') == 'active', 'Original service not active')
require(property('User') == 'hifyapp', 'Unexpected service user')
pid = property('MainPID')
require(Path('/proc/'+pid+'/exe').resolve() == JAVA, 'Different runtime Java')
environment = Path('/proc/'+pid+'/environ').read_bytes().split(b'\0')
keys = {entry.split(b'=',1)[0] for entry in environment if b'=' in entry}
require(not keys.intersection({b'JAVA_TOOL_OPTIONS',b'JDK_JAVA_OPTIONS',b'_JAVA_OPTIONS'}), 'Existing Java options must not be overwritten')
require(not any(arg.startswith(b'-Djavax.net.ssl.trustStore') for arg in Path('/proc/'+pid+'/cmdline').read_bytes().split(b'\0')), 'Existing explicit trust configuration')
del environment, keys
fingerprint = call(['openssl','x509','-in',str(CERT),'-noout','-fingerprint','-sha256']).split('=',1)[1].replace(':','').lower()
require(fingerprint == PIN, 'Source certificate fingerprint differs')
require(shutil.disk_usage('/etc').free > 200_000*1024 + CA.stat().st_size*3, 'Insufficient space')
idle()
old_jar, old_key = sha(JAR), metadata()
observed.update({'beforePid':pid,'jarBefore':old_jar,'keyMetadataBefore':old_key,
                 'defaultCaSha256':sha(CA),'sourceCertificateSha256':PIN})
call([str(JAVA.parent/'javac'),'-d',str(WORK),str(WORK/'TrustProbe.java')])
java_probe('default')
TRUST.parent.mkdir(mode=0o755)
shutil.copyfile(CA, TRUST)
TRUST.chmod(0o644)
call([str(JAVA.parent/'keytool'),'-importcert','-noprompt','-alias','teacher-workbench-20261006',
      '-file',str(CERT),'-keystore',str(TRUST),'-storepass','changeit'], timeout=30)
TRUST.chmod(0o644)
java_probe('audit',str(CA),str(TRUST))
shutil.copyfile(WORK/'TrustProbe.class', TRUST.parent/'TrustProbe.class')
(TRUST.parent/'TrustProbe.class').chmod(0o644)
java_probe('trusted',str(TRUST),as_user=True)
java_probe('wrong-host',str(TRUST),as_user=True)
observed['trustStoreSha256'] = sha(TRUST)
idle()
stopped = False
try:
    stopped = True
    call(['systemctl','stop','hify'], timeout=120)
    require(property('ActiveState') == 'inactive', 'Hify did not stop')
    idle()
    with DROP.open('x') as file:
        file.write('[Service]\nEnvironment="JAVA_TOOL_OPTIONS='+OPTIONS+'"\n')
    DROP.chmod(0o644)
    observed['dropInSha256'] = sha(DROP)
    call(['systemctl','daemon-reload'])
    call(['systemctl','start','hify'], timeout=120)
    require(health(), 'Hify health did not recover')
    new_pid = property('MainPID')
    require(new_pid != pid and new_pid != '0', 'No new service process')
    env = dict(item.split(b'=',1) for item in Path('/proc/'+new_pid+'/environ').read_bytes().split(b'\0') if b'=' in item)
    require(env.get(b'JAVA_TOOL_OPTIONS') == OPTIONS.encode(), 'Actual process did not receive trust options')
    del env
    require(sha(JAR) == old_jar and metadata() == old_key, 'Application or master-key metadata changed')
    require(sha(CA) == observed['defaultCaSha256'], 'System public truststore changed')
    java_probe('trusted',str(TRUST),as_user=True)
    observed.update({'result':'passed','afterPid':new_pid,'actualProcessOptionsMatch':True,
                     'healthHttpStatus':200,'jarUnchanged':True,'masterKeyMetadataUnchanged':True,
                     'systemCaUnchanged':True,'dropIn':str(DROP),'trustStore':str(TRUST)})
except BaseException as error:
    observed.update({'result':'failed','errorType':type(error).__name__})
    if stopped:
        try:
            if DROP.exists():
                DROP.rename(WORK/'failed-40-workbench-trust.conf')
            call(['systemctl','daemon-reload'])
            call(['systemctl','restart','hify'], timeout=120)
            observed['oldConfigurationRestoredHealthy'] = health()
        except BaseException as rollback:
            observed['rollbackErrorType'] = type(rollback).__name__
    raise
finally:
    (WORK/'result.json').write_text(json.dumps(observed,indent=2)+'\n')
    (WORK/'result.json').chmod(0o600)
    print(json.dumps(observed,indent=2),flush=True)
