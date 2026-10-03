# Workflow 冻结知识语料证据

任务 SPEC-WORKFLOW-KNOWLEDGE-001；基线 6c06f0d。仅使用独立 H2 与 Testcontainers pgvector/pg16；未访问共享服务或真实模型/凭据，未部署，未跑浏览器。HTTP 使用真实 Spring MockMvc + 服务/数据库，没有 mock 检索结果；取消/关闭专项夹具仍使用阻塞 mock 来控制交错。

证据目录：`harness/evidence/SPEC-WORKFLOW-KNOWLEDGE-001/SPEC-WORKFLOW-KNOWLEDGE-001-20261003T211653Z-11fb17fe/`。

## Red → Green

- `red-workflow-corpus.log`：4 项，3 failures、0 errors/skip。旧版本读到了十五天而非七天；草稿伪造快照被接受；原文篡改仍执行成功。第四项“未冻结的归档分块不能回读”本来通过。
- `green-workflow-corpus.log`：初版 H2 6 项通过。此时还不能声称 PostgreSQL 可运行。
- `green-workflow-pg.log`（文件名表示预期，不表示结果）：PG 8 项中 7 failures，文档索引失败。模块 17 项、H2 8 项、取消 1 项、关闭 6 项通过。保留失败证据，不用后续绿灯覆盖。
- `red-pg-timestamp.log`：独立 PG 冻结空库 1 error，直接捕获 `PSQLException: Can't infer the SQL type to use for an instance of java.time.Instant`。定位索引 INSERT、归档 UPDATE、语料 INSERT 的共同 JDBC 参数问题。
- `green-workflow-pg-portable-time.log`：改用显式 Timestamp 后 44 项通过、0 skip（Workflow 模块 17、H2 冻结链路 10、PG 同组 10、HTTP 取消 1、生产关闭/重启 6）。包括真实数据库发布失败回滚，以及旧 Agent 会话继续七天/新会话十五天。

## 范围

源码使用公开 KnowledgeRetrievalPort，发布时服务端生成 corpusVersionId/manifest，快照进入 DSL/checksum，不修改草稿。冻结读取校验 ID/base/revision/count/manifest 和原文摘要；归档 canonical 引用回读保留且需准确摘要。不改相关性排序、FinishGate 或旧版本数据。

现有取消与关闭测试只把 mock 检索入口更新为 searchSnapshot，并提供合法发布快照，原中断/不推进 END/重启恢复断言没有放宽。详细兼容边界见 `docs/spec/SPEC_WORKFLOW_KNOWLEDGE.md`。

最终原子命令和 migration/harness 门禁以本目录 command.log、verification.json 为准；中间绿灯不代替最终验收。无新迁移，PG 矩阵新增的 WorkflowKnowledgePostgresTest 必须 10 项且 0 skip 才放行。
