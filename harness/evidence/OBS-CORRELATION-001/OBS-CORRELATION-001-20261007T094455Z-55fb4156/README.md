# OBS-CORRELATION-001：失败后中止的六范围执行

代码基线 `67cfee761b956caae319c0a216f7b4909c2d705e`。本轮从
2026-10-07 17:44:55 CST 开始，18:58:48 主动结束失败执行，runner 于 18:59:25
以 143 退出并将任务记为 blocked。**没有完成六范围，没有通过，不是发布验收。**
目标仍是修复后重新进行完整验证，不用下面的部分结果替代。

## 实际完成与未完成

| 范围 | 原始结果 | 限定 |
| --- | --- | --- |
| harness | 五步 exit 0；Python 原日志 84 tests / OK | 当前 run 的事实，不沿用历史数字 |
| migration | 15 类、126 条记录，1 failure、0 errors/skip | 完整运行结束，Maven exit 1 |
| backend | 已落盘 54 份逐类 txt，421 条记录、2 failures、0 errors/skip | 主动中止，不是完整 backend；不能以此作通过率分母 |
| runtime / eval / frontend | 本轮未执行 | 不引用旧 run 填补 |

`backend-tests.tests.json` 按原验证器记录 commandExitCode=143、result=failed 和
summaryError；其中 totals=0 是无法形成完整有效摘要的初始化值，不表示没有执行测试。
421/2 是对保留的逐类 txt 的补充计数。Maven 日志中的 73 是 app 模块当时的小计，
不能与 reactor 内其他模块混成完整结果。原 JSON 未改写。

verify 被主动结束，因而没有生成 verification.json；不补造 schema 3 的 passed/完整
manifest。steps.tsv 只含已经返回的步骤，backend 日志及摘要另行原样保留。

## 三条失败记录对应两个问题位置

1. PG 的 KnowledgeMemoryPostgresTest 继承的
   `resumeKeepsFrozenKReferencesAfterArchiveAndDoesNotRetrieveAgain`：40.55 秒，
   `KnowledgeFinishIntegrationTest:89 -> chatWith:106 -> await:118` 抛 run timeout。
2. H2 的 KnowledgeAdmissionShutdownTest 同一继承方法：34.08 秒，栈和失败位置相同。
   两者都停在**首次 Run**，尚未执行 archive/resume，不能宣称恢复业务断言失败。
   下一次诊断优先看共享提交、调度、检索、模型、终态写入与轮询阶段；尚未确认共同根因。
3. WorkflowKnowledgeReviewTest 的元数据失败用例：80.76 秒，第 115 行 observed.isTrue
   失败，后续 FAILED 持久化、脱敏、旧分块保持断言未通过。原五秒等待仍在，mock 仍在
   dispatch 前构造。原文的 AtomicBoolean(true) 不能作为忽略失败的理由：异步对象在读取
   和渲染之间可能变化，但本轮没有足够时间戳证明这一解释。

这两份失败方法的源码与 05ab7a0 无差异；backend 相对该基线仅有 RequestCorrelationTest
的测试补强。源码未变不能证明失败无产品因素，也不能用旧绿灯关闭本轮失败。

## 环境与诊断，均不作为根因结论

- JDK 17.0.19；Maven heap 256MiB，Surefire heap 768MiB，Spring context cache 2。
- 独立 Colima `hify-verify-20261004`：1 CPU / 1GiB；没有切换全局 Docker context。
- 主机 16GiB；执行中观察到 swap 约 20–21GiB。default 与 dify profile 也在运行。
  这是观测，不是对照实验；没有停止其他项目。
- `diagnostics/gc-backend.log` 记录 18:15:36 的 RedefineClasses 安全点 26.523 秒、
  18:16:24 的 G1CollectForAllocation 安全点 10.130 秒。两者不是同一种停顿，不能统称
  “GC 导致测试失败”；没有 dispatch/await 精确起点，不能据此归因。
- 18:28:11 尝试一次 jcmd Thread.print -l，30 秒观察上限后超时，输出仅 PID（7 字节）。
  没取得线程栈，不作为死锁证据。这次 attach 是诊断干预，不能称完全无干预运行。

## 为什么结束、结束了什么

已确认 migration 和 backend 有失败，本轮不可能通过。为转入定向诊断，18:58:48 在
核对 PID/父子关系/命令后，暂停 verify 调度，向自己的 Maven/Surefire 发 TERM，保留
maven_step 生成的非成功摘要；随后结束 verify，使 runner 正常记录非成功状态。不是因
观察超时就假定进程停止，也没有删报告、改时限或把中止当通过。
19:00:24 已确认测试 JVM 退出；独立 Docker context 无运行容器，之后只停止自己的验证
profile（19:02:42 完成）。没有停止 Dify/default，没有改 132。

原始 XML 含环境/输出，不提交；逐类 txt、步骤日志、JSON、诊断日志按 SHA256SUMS 保留。
这些是本机执行记录，不是签名认证；本机路径/用户名在原始日志中保留。
