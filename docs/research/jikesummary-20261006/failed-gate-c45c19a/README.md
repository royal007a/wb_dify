# c45c19a 首轮完整门禁：失败且主动中止

这不是通过记录。固定源码 `c45c19a9837b71e9f44113441f23d7d495f55750` 请求运行 harness / migration / backend / runtime / eval / frontend。运行中已产生明确失败，2026-10-07 02:33:47 CST 主动对本次专属进程组 1883 发 SIGTERM；会话实际返回 143，随后核对六个目标 PID 均不存在。没有因输出暂时沉默就判成已停止，也未在原进程活着时开启第二轮数据库测试。

中止理由：这轮已无法通过，同时持续出现线程饥饿与严重启动延迟，需要释放本次测试占用，先定位失败再恢复完整验证。只终止本次 verify shell、Maven wrapper/Maven 与 Surefire 子进程；没有执行停止业务服务、其他项目容器、其他 Colima profile 或清理共享缓存的命令。自己的 Testcontainers 随进程结束回收。

## 已有结果

- Harness Python：78 项，1 failure。假命令屏障未触达，不等于部署信号处理行为已经被验证失败；保留完整 Python 原日志。
- Migration：15 个预期类中 9 个已有完整 XML，共 79 项，4 failures、2 errors、0 skipped。其余 6 类没有完整 XML，不能并入通过分母。
- 已报告失败：索引元数据故障后回读 SUCCEEDED；知识完成验证等待 Run 终态超时；Postgres 并发测试的终态等待超时、SSE 只收到 heartbeat；两条 RunShutdown 的原 60 秒超时。具体原断言/堆栈见逐类 `.txt`。
- 中止时 `WorkflowKnowledgePostgresTest` 正在初始化。backend/runtime/eval/frontend 尚未开始。这轮没有生成 `verification.json`，不得补造 passed 或声称六 scope 均已执行。

资源压力是观察，不是六个失败的统一根因。原超时、断言和产品代码没有因这轮失败而被放宽；后续隔离绿灯也不能覆盖掉这里的红灯。

## 留存与核对

本目录保存 9 个 Maven 原始文本报告、原 `harness-python-tests.log`、中止时 `steps.tsv`。复制后逐字节比对一致，SHA 与源树身份在 `interruption.json`。XML 与全量 migration 日志保留在原独立目录 `/Users/weberzhao/hify/harness/evidence/manual/course-20261007-c45c19a/`；其 SHA 写入本记录，但没有将这些完整文件提交进本目录。不把摘要或 SHA 当作未提供文件的内容证据。

## 接下来的诊断与最终要求

先固定 c45c19a 的同一索引用例，单方法运行、不修改原断言，使用明确记录的 512MiB JVM 堆和单 context 缓存；再在需要时使用 `../indexing-visibility/postgres-observation.patch` 区分故障注入、提交与读取先后。改变资源/运行范围是诊断变量，不是原失败自动归类为环境原因的依据。

新检索切片仍需其自己的完整六 scope 和独立 review。首轮中止不是缩小最终验收范围，也没有把任务标为完成或部署。
