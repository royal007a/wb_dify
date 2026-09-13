# Phase 0 原型对齐清单

2026-09-12 已交付并部署首个纵向闭环，测试和运行证据见 `INITIAL_RELEASE.md`。Phase 0 尚未全部完成；下表用于区分已经闭环、部分对齐和仍待实施的项目，避免把“初版可运行”误报为“目标架构完成”。

| 状态 | 当前实现 | 目标/待决策 | 剩余验收 |
|---|---|---|---|
| 已闭环 | Maven parent + common/provider/tool/mcp/agent/chat/knowledge/workflow/app | 模块声明与目录一致，业务依赖由 reactor 排序 | reactor clean test/package 已通过；后续补架构依赖自动测试 |
| 部分闭环 | 已切换 Spring MVC + SseEmitter，SSE replay 经 Nginx 验证 | Provider 流式类型不泄漏，取消传播到阻塞 I/O | 真实流式、强制取消、100 连接容量测试 |
| 部分闭环 | MyBatis-Plus 配置/分页/填充/逻辑删除已就位；已验证 Repository 暂留 JPA | 写 persistence ADR；不混用三套通用 ORM | 分模块逐仓迁移；JSONB、vector 查询原型通过后定案 |
| 部分闭环 | PostgreSQL 16 + Flyway 已部署；H2 用于快速测试 | PostgreSQL + pgvector 是验收数据库 | Testcontainers 并发幂等/CAS 已通过；待 pgvector Repository 测试 |
| 部分闭环 | `V1__baseline.sql` 空库迁移已通过 | 增加 pgvector schema/索引与恢复方案 | 回滚策略、HNSW 和恢复 smoke |
| 部分闭环 | Nginx + 三容器脚本 + `compose.yaml`；Redis 未启用 | Redis 保持可选 | Compose plugin/CI smoke；有 Redis replay 测试 |
| 部分闭环 | Vue 3 + TS Playground，build/typecheck 通过 | generated client + Vue Query；Pinia 仅 UI/编辑态 | mock 浏览器 E2E、状态层对齐 |
| 已闭环 | Run API 使用短事务；旧 `ChatService` 与 `/api/chat` 已删除 | 所有外部 I/O 离开数据库事务 | H2 回归与部署 404 验证通过 |
| 待实施 | 多个 Controller 仍直接访问 Repository/Entity | Controller -> application service -> DTO | 架构测试禁止跨层/跨模块引用 |
| 部分闭环 | deadline、取消检查、工具/估算 token budget、read policy、结构化终态和 SQL CAS | 精确 token/cost、工具 timeout | PostgreSQL 8 路 CAS 仅一赢家；待完整 failure/fault 矩阵 |

Phase 0 完成后更新或删除本文件；未决 persistence ADR 必须在迁移或新增第一个目标态 Repository 前定稿。
