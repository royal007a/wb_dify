# 冻结知识复核补强证据

任务 SPEC-WORKFLOW-KNOWLEDGE-002，基线 a5f611c。对应 reviewer 对 6c06f0d..60e74c8 的六项 P2；只在独立 H2/Testcontainers pgvector/pg16 验证，无共享库、真实凭据、付费模型或部署操作。

证据目录：`harness/evidence/SPEC-WORKFLOW-KNOWLEDGE-002/SPEC-WORKFLOW-KNOWLEDGE-002-20261003T214143Z-a52b174c/`。

## 反例与修复

- `red-review.log`：16 项（含继承的原10项）中 5 failures、0 errors/skip。缺快照的旧 Workflow 与携带真实 corpus 的旧客户端快照，均仍被 Agent 发布接受；DSL 改动不重算 checksum 仍能发布；相反业务优先级导致 Agent 首先锁 B 而非 A（测试等待 A 的 latch 超时，未声称此轮实际触发 PG 死锁）；数据库类型判断没有使用事务连接 callback。
- 修复：发布 DSL 顶层 envelope 不属于客户端 Draft DTO；发布、绑定/Agent 发布、执行复用 checksum/格式校验。旧知识版本拒绝，不回写，提示完整的 Workflow→Agent→新会话迁移顺序。Agent 按 base ID 取锁后再按原绑定顺序构建快照；类型判断改为 JdbcTemplate ConnectionCallback。
- `green-review.log`：54 项通过、0 skip（Workflow 模块17、H2复核16、PG复核16、Workflow HTTP5）。H2/PG均执行校验后第二连接更新原文的交错，本轮仍返回旧原文，下轮检测损坏；明确断言 JDBC 隔离级别 REPEATABLE_READ，而非仅检查注解。
- `green-review-pg-lock.log`：强化锁测试后，PG复核16项全部通过、0 skip。Agent持有A时启动Workflow发布，通过 pg_stat_activity 观测真实的 knowledge_bases 锁等待；释放Agent后两个事务均完成。测试不把线程启动/latch到达单独当作数据库阻塞证明。
- 类型判断测试把 ConnectionCallback 接到 DataSourceUtils 返回的实际事务连接并断言事务绑定；旧实现不经过 callback，红灯。未做连接池耗尽负载测试。

## 兼容与未保证

向量列不纳入 manifest；重建 embedding 可改变未来调用的排序/topK，冻结保证的是成员与原文，不承诺排序永不变化。服务端 envelope/checksum 是正常写入路径的版本识别和完整性检测，不是签名，不能抵御有数据库写权限者联合篡改。

过渡版本 60e74c8 无 envelope，和更早旧知识图一样需先重新发布 Workflow，再发布 Agent并创建新会话。本片未上线，所以未自动迁移生产数据。批量展示能力摘要仍是导航信息；真正绑定/发布/执行在使用时再校验。Chat 前置执行异常的分类、FinishGate、空/无关证据问题仍分别留在后续任务。

原 WorkflowControl/Engine 单测 fixture 改成合法 checksum/envelope；旧非法图测试改为合法 checksum，继续检验图本身被拒绝，没有降低原断言。原切片结算夹具的修复见 SPEC_WORKFLOW_KNOWLEDGE.md。

H2 交错只说明读取的内存候选没有被后续写入改变，并在 JDBC 层确认隔离级别设置；由于排序不二次查库，它不能证明隔离的实际防漂移效果。只有 PG 的校验后重新查询排序路径提供这项证据。最终定向命令与 harness/migration 门禁以 command.log 和 verification.json 为准；上述中间通过不代替最终门禁。没有全仓库、浏览器或服务器部署证据。

## 最终门禁

源码 9183f05，测试与契约 356f9e6。2026-10-04 05:52 CST 完成：command.log 定向 67 项通过（模块17、应用50），migration-postgres.log 53 项通过、0 skip；harness 与所有 migration-not-skipped 断言通过。包含缓存、Agent发布、图验证、结算、取消、生产关闭/重启的回归。H2/PG继承原10项，因此各阶段数字不可累加成互不重复的用例数。已送独立 reviewer 复验，复验状态另记，不用门禁替代审查。

独立 reviewer 静态复验认为原六项 P2 已修复或边界关闭，无新 P0/P1；未运行测试/读取 verification.json。新增 P2：Agent 冻结的 Workflow checksum 尚未和执行行对比；索引侧 isPostgres 仍额外借连接且失败退回 H2；H2 隔离证据措辞偏强（已在上方更正）。前两项登记 SPEC-KNOWLEDGE-INTEGRITY-003，未借此宣布修复。
