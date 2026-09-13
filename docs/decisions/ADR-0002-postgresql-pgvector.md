# ADR-0002：PostgreSQL + pgvector 作为单一事实源

- 状态：Accepted
- 日期：2026-09-08

## 背景

课程示例使用 MySQL 保存业务数据、独立 PostgreSQL/pgvector 保存向量。一人维护两个数据库会增加备份、恢复、迁移和跨库一致性成本。

## 决策

一期统一使用 PostgreSQL 16 + pgvector。Redis 仅作为可重建缓存、限流和短期事件 replay，不是 Conversation、Message、Run 或任务的唯一存储。

## 后果

部署和恢复链更短，RAG 元数据与向量可在同一数据库治理。需要用真实 PostgreSQL/Testcontainers 验证迁移和查询，不能依赖 H2 证明兼容性。

## 重新评估触发器

chunk 达百万级且 pgvector 检索/维护持续不达 SLO，再以真实基准评估独立向量库。

