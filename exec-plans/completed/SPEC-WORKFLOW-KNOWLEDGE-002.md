# 冻结知识快照复核补强

只处理独立 reviewer 的六项 P2，不改变检索质量、FinishGate 或线上配置。

1. Agent 发布和执行共用不可变 DSL 完整性/发布格式校验。旧知识版本缺服务端顶层发布标记时 fail-closed，要求先重新发布 Workflow，再发布 Agent 并创建新会话。校验 checksum 不能单独证明来源，因此顶层标记只由服务端发布 writer 产生，不属于客户端 Draft DTO；旧 config 中自填真实 corpus ID 仍拒绝。不回写旧版本。
2. Agent 与 Workflow 冻结相同多库时统一按 base ID 取锁；保留绑定原优先级/顺序。用 latch 控制真实数据库并发发布，验证无反序锁。
3. 数据库类型判断复用 JdbcTemplate 当前事务连接；H2/PG 用第二连接在校验后修改原文，验证检索不混入新内容，并断言真实连接隔离级别。
4. 明确 manifest 只冻结成员与原文，向量重建会改变排序，不承诺 topK 恒定；旧源码/数据库同时被篡改不是签名防护。
5. 新测试先记录失败，再定向和 migration 门禁；不接触 hify-cc、共享服务、132 或真实凭据。无新增表迁移。
