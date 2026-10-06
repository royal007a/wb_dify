# 索引故障测试的事务可见性诊断

2026-10-07，本记录是诊断，不是修复验收，也不关闭 PostgreSQL 原失败。

## 实际失败与待验证解释

固定主树 `c45c19a9837b71e9f44113441f23d7d495f55750` 的六 scope 门禁仍在运行。已经完成的 `WorkflowKnowledgeReviewPostgresTest` 有 19 项、1 failure：`indexingMetadataFailurePersistsFailureWithoutDeletingExistingChunks` 期望 FAILED，实际读到 SUCCEEDED。原报告在主树本次 evidence 的 `migration-postgres-bsoowomc/reports/`；这里没有把仍在运行的门禁标为完成，也没有修改那次的输出。

静态先后关系：测试通过异步入口再次索引已有成功文档；`observed.set(true)` 在构造元数据故障、调用生产 callback 之前。生产 `DocumentIndexingService` 在 REQUIRES_NEW 事务内先写 RUNNING/PROCESSING，再检查元数据；捕获故障后写 FAILED，事务完成才提交。读取端五秒轮询期间可能仍看见上一轮成功状态。`observed=true` 不能证明异常已注入或事务已完成。

这是一条待验证的解释，不排除真正的持久化、异步入口或测试隔离缺陷。原门禁没有留下故障注入与事务完成的独立时间戳，单凭机器繁忙不能确定根因。

## 受控实测

- 使用固定主树的真实 `DocumentIndexingService`，单线程 executor、真实 H2 数据库和 `DataSourceTransactionManager`。
- Repository 是 Mockito 适配器，只将状态写进探针的两张简化表；不是 JPA/Spring Boot/PostgreSQL 全链路。
- 在事务绑定的元数据检查处设置屏障，明确停在异常注入之前。读取端五秒轮询，实际 57 次，仍为旧 SUCCEEDED。
- 释放屏障后注入 SQLException；等 worker Future 完成（此时事务已经返回）再读，任务和文档均 FAILED，错误不含私有细节，旧分块逐字未变。
- 没有延长原测试的等待、修改期望、修改产品或重跑 Maven。探针自身的屏障保护为 60 秒，不是产品时限或原测试的新验收阈值。

`probe.log` 是第二次执行的原始 stdout/stderr，退出 0；第一次隔离执行也是相同结果，轮询 66 次，其输出只保存在会话工具记录，不冒充本文件的原日志。次数取决于调度，不是稳定性能指标。

| 工件 | SHA-256 |
|---|---|
| 生产 DocumentIndexingService.java | `2a73f71b1449c22493c0b961af44d3da15b0ed99c941a002e265a096e865b6a8` |
| IndexFailureVisibilityProbe.java | `3ccc4c98b3403268ae304eea3c5c5fc6903b2fbf57908e3adbeb53fc6edd309c` |
| probe.log | `3ad9d66575da5fc2e73678e64eacd76d6ad0d450c08dd2b4729c65537bbd09d1` |

## 复现

```sh
python3 docs/research/jikesummary-20261006/indexing-visibility/run-probe.py \
  --source-root /Users/weberzhao/hify \
  --classpath-report /Users/weberzhao/hify/harness/evidence/manual/course-20261007-c45c19a/migration-postgres-bsoowomc/reports/TEST-com.hify.api.KnowledgeShutdownPostgresTest.xml \
  --jdk-bin /Users/weberzhao/software/jdk-17.0.19.jdk/Contents/Home/bin
```

依赖从既有 Maven XML 的 classpath 读取，只重新编译指定生产类和探针；输出在独立临时目录，JVM 上限 128MiB。不重写 Maven classes/reports，不调用服务或模型。这不是干净 reactor 构建的证明。换机器需指定相应已构建依赖和报告。

## 下一项有区分力的验证

完整门禁结束后，固定同一源码，在原 PostgreSQL 用例里独立观察：进入 callback、真正抛出元数据异常、事务 afterCompletion、读取状态。只有证实原失败发生在事务提交前，才能将其归类为该时序问题。若提交完成后仍为 SUCCEEDED，继续查真实持久化路径。任何修复仍保留 FAILED、错误脱敏、旧分块完整三个断言；不以这次 H2 成功替代原 PG 验收。
