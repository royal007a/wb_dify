# RUNTIME-001 — Runtime 能力租约与历史提交协议

## Outcome

单个 Run 在固定能力快照下执行；取消或过期租约不能执行工具；每次模型/工具结果只在 canonical history 已持久化、回读、投影后确认。

## Scope / Non-goals

- In：能力摘要、Run 字段、执行租约、history commit/revision、operation conflict、事件和测试。
- Out：write 工具、分布式 exactly-once、上下文压缩、子 Agent。

## Steps and checkpoints

1. 先写能力摘要、租约和 operation conflict 契约测试。
2. 新增数据库迁移、实体、Repository 和 committed-history writer。
3. 接入 QueryLoop/RunApplicationService，补事件与恢复行为。
4. 跑 runtime、migration、backend 全矩阵。

## Acceptance and evidence

- Verify scopes：backend,migration,runtime。
- Evidence：由 Harness 生命周期记录。

## Rollback / recovery boundary

仅新增表/列和兼容代码；Git 可回滚代码。迁移为前向新增，不宣称自动回滚数据库。
