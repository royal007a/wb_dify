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

## 后续准备（非执行结果）

`postgres-observation.patch` 只给固定 c45c19a 的原测试增加单调时间/阶段观察点，未修改产品。保留原五秒轮询、异常类型/内容和全部断言；末次状态查询只执行一次，先记录其值再断言。新增事务 afterCommit/afterCompletion 回调不执行 SQL。日志会影响调度，因此即使后续通过也不能排除原竞态。

补丁应用于独立 scratch 副本，已用 JDK 17 和原报告 classpath 编译通过；此时没有执行 Spring 或 PostgreSQL。编译后的测试源码 SHA `a3bfcc497a4dd1e984918bd117ed52c8c3a7f6d8655c94d65fbc894f334c6b4d`。主工作树和产品源码未修改。

首轮完整门禁后来因实际红灯和资源压力主动中止，见 `../failed-gate-c45c19a/README.md`；此前“仍在运行”的表述是本探针完成时的阶段记录。后续首先运行原版未插桩方法作为对照，不将编译准备计为 PG 验收通过。

## 原 PostgreSQL 方法的独立对照（已执行）

2026-10-07 02:43:54 CST，终端会话 22712 实际退出 0：固定 c45c19a 的 `WorkflowKnowledgeReviewPostgresTest#indexingMetadataFailurePersistsFailureWithoutDeletingExistingChunks`，1 found / 1 successful / 0 failed、skipped、aborted。使用原构建的测试 class，未应用本目录的 observation patch，未延长原五秒轮询或改断言；主树保持干净。

JUnit Launcher 直接选择这一个方法，依赖来自原 Maven XML 的 classpath；原 test-classes 排在 scratch 编译目录之前，所以载入的父测试没有插桩。JDK 17，`-Xmx512m -XX:ReservedCodeCacheSize=64m -Dspring.test.context.cache.maxSize=1`，自己的 Testcontainers PostgreSQL，Docker context 为 `colima-hify-verify-20261004`。没有跑模型或生产服务，也不是 Maven 全套或干净构建。Spring 启动日志为 427.045 秒，总测试 493.558 秒；改变运行范围和资源条件是诊断变量。

留存边界：`postgres-original-prefix.log` 是工具捕获并逐字保存的前缀（到 02:42:56 的 context 注册），**不是完整日志**；最后一次工具返回的 JUnit 结果另以 `postgres-original-result-excerpt.txt` 摘录。其间 HTTP 请求与启动日志留在会话工具记录，没有拼接冒充完整原日志。后续运行从启动时直接重定向到独立文件，避免依赖会话内存留存。

结论仅限这次原方法通过。它没有复现第一轮失败，也没有故障注入/提交先后的观察点，因此 **不能确认原失败根因，更不能关闭完整门禁红灯**。H2 屏障只证明一种可能机制，仍不作为原 PG 根因证据。不因这次绿灯修改产品或放宽测试。下一次完整验证保留同一原断言；若再次失败，再使用观察补丁区分时序。
