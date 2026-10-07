真实 H2 重启正反例复验：仅本轮两方法通过，历史红灯保留

源码与执行范围
- HEAD 0087ffcab834261a736287e3f6ebe03ffa1f7db4；开始、结束时工作树干净。
- 运行中没有源码或提交变化。backend tree ca0bd54331f30d2b3f8161526e56801fa9cad0cf，
  与3ebe106四范围运行相同；前端/部署没有参与本次测试。
- 本轮没有改产品或测试，没有放宽类级 @Timeout(45) 或原有10秒/5秒断言。
- 真实Spring关闭与重新启动、真实隔离H2和runExecutor；模型为测试桩。
  没有连接外部模型、PG或132，没有启动Docker，也没有停止共享服务。
- Maven增量构建，不是clean重构建；原XML保留在本轮私有目录，不提交JVM属性。

命令（backend目录）
JAVA_HOME=<jdk-17.0.19> MAVEN_OPTS='-Xmx256m -XX:ReservedCodeCacheSize=96m' \
JAVA_TOOL_OPTIONS='-Xmx768m -XX:ReservedCodeCacheSize=128m -Dspring.test.context.cache.maxSize=2' \
mvn -B -o -pl hify-app -am \
  '-Dtest=RunShutdownIntegrationTest#interruptedModelIsRecoverableAfterActualApplicationRestart+expiredInterruptedModelDoesNotRestartItsBudgetAfterActualApplicationRestart' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhify.test.reportsDirectory=<fresh-private-directory>/reports test

实际结果
- 2026-10-07 13:23:03 CST结束，Maven exit 0，总耗时30.529s。
- JUnit tests=2, failures=0, errors=0, skipped=0，suite时间26.341s。
- 过期恢复：17.226s，模型零调用、TIMEOUT唯一终态事件、无assistant/delta、
  checkpoint没有被恢复或改写。日志到达model-recovery-assertions-passed。
- 及时恢复：9.097s，COMPLETED、recovered、assistant=1、checkpoint.restored，
  恢复后模型恰好调用一次。日志到达model-recovery-assertions-passed。
- 本次第一/第二上下文的阶段记录保留在原日志，不改写为性能基准。
  H2/Flyway版本兼容警告照实保留。

与旧失败的关系
recovery-expiry-9a6d8b8/中05:15的两项ERROR完全保留。那次运行的产品代码早于
后续ExecutionControl/本地子预算修复；同一重启测试源码与本次相同。本次启动阶段
明显更快，但这不是受控的单变量实验，不能归因到内存、缓存、CPU或某个修复，
也不能宣称已定位或根治所有偶发失败。新记录不能覆盖旧记录。

尚未验证
本次不是整个RunShutdownIntegrationTest类、PG重启反例、backend或六scope验收。
没有做恢复反例的突变验证，没有新增passed verification。tasks.json仍pending。
此前四scope绿灯和本次两方法绿灯不合并冒充同一次完整门禁；未合并main、未部署。

文件SHA-256（逐字原始日志，未重排/过滤）
maven.log: 5a873498db1fbb7179a902323fa6437593d3c3d663661506b6a5b0ed8aa7369a
junit-report.txt: 9405f31c110923aecc31961bf1edb5a0b4dddc36433a48e8fbb891b6796d053e
未提交XML: daf83a487a387ab17e789a99d494ffadcc7aa8f403d11a56adf1c6868b38be3a
