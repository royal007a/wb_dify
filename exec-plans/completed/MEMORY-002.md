# MEMORY-002 — 结构化摘要与统一细节目录

## Outcome

摘要是有来源的模型导航，DetailRef 是回到 canonical history 的可校验证据指针。

## Scope / Non-goals

- In：Summary/Claim/DetailRef schema、确定性结构化 composer、索引器、完整性与冲突状态。
- Out：外部 ES、图数据库、无来源生成式摘要。

## Steps and checkpoints

1. 先写领域状态和引用完整性测试。
2. 新增迁移、实体、Repository 与 canonical history 派生索引器。
3. 建立结构化 summary composer 与覆盖/冲突校验。
4. 跑 backend/migration/runtime。

## Acceptance and evidence

见 `harness/tasks.json`；证据由 Harness 记录。

## Rollback / recovery boundary

新增表均为派生状态，可由 canonical history 重建；迁移只前向新增。
