# 隔离诊断：A05 / A06

基线 `973257c` 的已编译 common 类（生产代码截至 `adc3f59` 未变化）。不启动完整应用、不修改数据库、不调用公网。临时 JVM 使用 `-Xmx256m`。执行器计数模拟 queued task，取消 probe 通过 latch 确认 worker 已进入，再通知取消；在 finally 清理线程**之前**检查是否收到 interrupt。

在仓库根目录、common 测试已运行并生成报告之后：

```sh
python3 - <<'PY'
import xml.etree.ElementTree as ET
import subprocess
report = ET.parse('backend/hify-common/target/surefire-reports/TEST-com.hify.common.CircuitBreakerServiceTest.xml')
classpath = next(p.attrib['value'] for p in report.findall('./properties/property') if p.attrib['name'] == 'java.class.path')
result = subprocess.run(['java', '-Xmx256m', '-cp', classpath, 'docs/evidence/spec-probes/AuditProbe.java'], capture_output=True, text=True)
for line in result.stdout.splitlines():
    if line.startswith(('A05 ', 'A06 ')):
        print(line)
print('exitCode', result.returncode)
if result.returncode:
    print(result.stderr[-2500:])
PY
```

2026-10-03 修复前新鲜输出：

```text
A05 missingHeader=500 invalidPage=500 unsupportedMethod=500 unsupportedMedia=500
A06 preCancelledPostSubmissions=1
A06 callerFailure=ExecutionCancelledException
A06 workerInterruptedBeforeCleanup=false
exitCode 0
```

解释：这里 exit0 只说明诊断程序正常结束，**不是行为通过**。期望四种客户端错误分别返回400/400/405/415；已取消请求不提交任务；取消运行应中断 worker。A05 使用 standalone MockMvc + 真实 GlobalExceptionHandler + 最小 Controller，只证明公共异常处理行为，仍需应用真实路由回归。A06 不依赖网络或真实供应商；不代表供应商端能够撤销已经执行的请求。
