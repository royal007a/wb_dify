# KnowledgeMemoryPostgresTest 整类复验

固定源码 `75af78dd1f9a6bc7e78f3d8ef1d67f67488d9e2c`；三次执行开始/结束工作树均干净。
不改生产代码、mock、10 秒等待或断言。使用增量 Maven，独立 Colima
`hify-verify-20261004`（1 CPU / 1GiB），未切换全局 Docker context，未操作 Dify/default/132。

| 执行 | 原始结果 | 判断 |
| --- | --- | --- |
| attempt1-skipped | Maven 0；18 条全部 skipped | 无效验证，未连接 Docker |
| attempt2-startup-error | Maven 1；21 errors | Spring 初始化错误，未到业务断言 |
| attempt3-aligned | Maven 0；21/21，零 failure/error/skip | 本次 PG 整类通过；不是六范围门禁 |

前两次是本次定向命令构造的遗漏，日志全部保留：

1. 未带正式 `harness/maven_step.py` 的 `-Dapi.version=1.44`，Docker 报 client 1.32
   低于最低 1.40；`disabledWithoutDocker` 导致全部跳过。Maven 0 不表示执行成功。
2. 补了 API 版本，但漏掉 `harness/verify.sh:configure_testcontainers` 设置的三组
   nonProxyHosts。JVM 属性里 SOCKS/HTTP 代理来自本机配置，回环不在排除项里；
   Flyway 经 pgjdbc 建连报 `UnknownHostException: 127.0.0.1`。21 项都是上下文错误，
   不能称作原首次 Run 超时再次复现。
3. 对齐 HTTP、HTTPS、SOCKS 的 `localhost|127.*|[::1]` 排除规则后，整类实际执行
   21 项并通过。原失败的 `resumeKeepsFrozenKReferencesAfterArchiveAndDoesNotRetrieveAgain`
   已包含在其中。外层耗时 58.914 秒；JUnit suite 43.56 秒；Spring 启动 28.662 秒。

准确参数与命令在每次 source.json，原返回值在 result.json。第三次驱动另外要求
21 条实际记录、零跳过/失败/错误才接受；21 与受审 expected-maven-suites 清单一致。
raw XML 只在本机保留，摘要记录原 XML 哈希；原日志、逐类 txt、GC/安全点日志和
vm_stat 随附。SHA256SUMS 对提交材料逐字计算，不宣称可信构建证明。

## 边界与下一步

原 gate 的 PG/H2 首次 Run 超时、索引失败均原样保留；本次单类和上一份 H2 两类绿灯
不能解释原来的全量失败，未定位根因，不排除时序、跨类交互或产品因素。
只证明截止前轮询未观察到终态，不能将旧红灯描述为连续十秒一直 RUNNING。
未放宽时限，未修产品，未跑完整 backend/迁移矩阵/六范围，未部署。
发布仍需当前固定源码的完整门禁，不拼接这些窄范围结果冒充 gate。
