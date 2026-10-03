# 知识入场关闭分类回归

任务 SPEC-KNOWLEDGE-FINISH-002，基线 95890e5。前一片门禁通过后自查出的交叉问题，不把旧通过数字当作此路径已验证。

证据目录：`harness/evidence/SPEC-KNOWLEDGE-FINISH-002/SPEC-KNOWLEDGE-FINISH-002-20261003T222452Z-1c171576/`。

`red-shutdown.log` 两项参数化测试均失败：显式挂起、检索中切到 stopping 再抛读取异常，最终都错误落成 FAILED / KNOWLEDGE_RETRIEVAL_FAILED。测试走真实 RunApplicationService 与数据库/事件，Spy 检索与生命周期信号；不是模拟真实进程关闭顺序。

修复只在读取来源的 catch 中优先识别挂起/关闭，重抛 ExecutionSuspendedException 交给已有 E1 结算。无关闭时严格来源拒答不变。测试要求 Run 保留 RUNNING、有 run.interrupted、无 run.failed、模型零调用、无 assistant 落库。未替换生产关闭逻辑，不在这里宣称 JDBC 可立即响应线程中断。生产销毁顺序仍由 RunShutdownIntegrationTest 及其既有 E1 证据验证。

green-shutdown.log 和最终 command.log 覆盖新分类、继承知识门禁、CompletionVerifier 与生产关闭集成回归；结果及源码 commit 在最终门禁后追加。无表迁移、PG SQL 改动、共享服务/业务数据/凭据访问或部署。

green-shutdown.log 首次回归出现 1 个旧夹具失败：RunShutdownIntegrationTest 的 Workflow 重启恢复原先用空知识结果作为成功输入，现在按严格门禁应失败。将恢复后的 fixture 改为一条有效候选，保持“中断痕迹、重启新 attempt 成功、assistant 仅一次”的全部原断言；未放松零候选拒绝规则。前一轮门禁的 RunShutdownPostgresTest 不覆盖这个 H2 Workflow 夹具，故此前通过不能证明它兼容。

green-shutdown-fixture.log 定向 54 项通过、0 skip（单位10，应用44：知识18、带关闭信号20、真实上下文关闭6）。其中继承的18项重复运行，不是20个全新场景。最终仍以 command.log 与 verification.json 为准。

最终源码 1c9ceab，2026-10-04 06:29 CST：command.log 54 项通过、0 skip；runtime 34 项通过、0 skip；harness 全通过，verification.json 的 headCommit 为 1c9ceab。此补强未重跑 PG SQL（未改动），前片 40a9238 的 migration 71 项证据不能伪称在新 commit 重跑。尚未做全仓库回归、浏览器验证或部署；独立只读复核待反馈。
