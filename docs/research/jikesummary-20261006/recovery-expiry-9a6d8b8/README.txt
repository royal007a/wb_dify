真实 H2 重启正反例：本轮失败，不能作为验收通过

源码身份
- 启动时 HEAD 4f69f00，测试代码存在工作区；编译/执行过程中没有再改 Java。
- 同一份 Java 测试随后提交为 9a6d8b8；该提交只改两份测试、预期数量和计划。
- 运行中另行提交的 543d490 只有课程笔记和阅读清单，不影响被测 Java。
- 无生产代码修改；没有启动 Docker、连接 PG 或生产服务器，没有停止 Dify。

命令（backend 目录，独立新报告目录）
JAVA_HOME=<jdk-17.0.19> MAVEN_OPTS='-Xmx256m -XX:ReservedCodeCacheSize=96m' \
JAVA_TOOL_OPTIONS='-Xmx768m -XX:ReservedCodeCacheSize=128m -Dspring.test.context.cache.maxSize=2' \
mvn -B -o -pl hify-app -am \
  '-Dtest=RunShutdownIntegrationTest#expiredInterruptedModelDoesNotRestartItsBudgetAfterActualApplicationRestart+interruptedModelIsRecoverableAfterActualApplicationRestart' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhify.test.reportsDirectory=<fresh-private-directory>/reports test

结果
2026-10-07 05:15:24 +08:00 结束，Maven exit 1，耗时 11:46。
JUnit suite: tests=2, failures=0, errors=2, skipped=0, time=567.473s。
- expiredInterruptedModelDoesNotRestartItsBudgetAfterActualApplicationRestart:
  305.845s，TimeoutException，原类级 @Timeout(45)。日志确实到达
  model-recovery-assertions-passed（298.871s）及第二上下文关闭。
  这说明当次执行经过了负例的业务断言，但方法仍是 ERROR，不能写成1/1通过。
- interruptedModelIsRecoverableAfterActualApplicationRestart:
  259.294s，同样45秒 TimeoutException；还保留 suppressed AssertionFailedError，
  原 awaitTerminal 的10秒等待后状态未终止。没有走到正例的最终业务断言。
  同轮日志还有 runExecutor 销毁等待超时，不能只凭启动慢就排除功能问题。

诊断边界
- 两个方法第一次 Spring 启动分别161.365s / 143.509s；第二实例启动阶段分别
  约125.040s / 84.436s。未扩大类级45秒、PG60秒或原10/5秒等待。
- thread-0510 是只对本轮 Surefire PID 11560 的 jcmd Thread.print，主线程
  位于正例第一个 Spring 上下文的组件扫描/文件读取，并非运行中的模型等待。
  它只是一个时间点的栈，不证明所有耗时或失败都由同一根因造成。
- 同轮 vm_stat 一秒采样看到持续 swap-in / swap-out；采样未作为原始文件保存，
  因此不把具体速率纳入仓库可复算结论。之前完整门禁原始资源记录仍单独保留。
- 原始日志未改写，包含 synthetic fixture 的路径/类名，无真实业务正文或凭据。
  原XML含JVM属性，留在私有新目录，不提交。测试结束后两个本轮Java PID已退出。
- 新PG反例未运行，尚未做本组反例的突变验证；没有生成 passed verification，
  完整六scope与独立review仍待完成。此红灯不替代、也不覆盖先前的红灯目录。

文件摘要
maven.log:
636163985faa835c073e061e8d3d89da83028660d31920630271ccc881dc97aa
junit-report.txt:
a107b45616321760383cde5b8699702ba1ee8d1bd41933c930152f997d364b8b
thread-0510.txt:
c7150b214b27857a64ad40219f222fae01eb78b676aa955f14fdfe9ea26ca4b5
未提交的原XML:
0cfb8580c0680e3351c9ee1a93902e6d49642cfb23e44f5aa9be2e9a5f5fa8e5

后续：先保留失败和精确源码，取得足够资源或确定其他可复现根因后再跑原正反例。
不接受任意终态，不把“负例业务断言走完”当门禁绿灯，不放宽时限，不自动改生产。
