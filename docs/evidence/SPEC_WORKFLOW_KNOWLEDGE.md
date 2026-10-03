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

## 第一次最终门禁失败

60e74c8 的定向命令 41 项通过；migration 组合 37 项中 2 errors，均为 WorkflowSettlementPostgresTest 的失败路径夹具。该夹具原来通过发布一个不存在的知识库，在运行时制造 FAILED；新契约已在发布时拒绝，因此夹具停在发布，而不是检验原有结算竞争。新 WorkflowKnowledgePostgresTest 的 10 项全部通过。原子任务保持 blocked，修正夹具后重新执行完整门禁；不能把这次组合写为通过。

独立 reviewer 对 6c06f0d..60e74c8 仅做静态阅读，未发现 P0/P1；提出旧 Workflow 在 Agent 发布时的拒绝/提示、旧客户端快照来源与 checksum、向量重建排序边界、多库加锁顺序、事务交错测试、数据库产品判断额外借连接六项 P2。静态复核不替代门禁，后续按反例处理。

## 最终复跑

be205dd 只修正结算测试夹具：先发布有效空语料，再破坏其 manifest，产生真正的执行失败；原四组成功/失败与取消竞争断言全部保留。复跑目录 `harness/evidence/SPEC-WORKFLOW-KNOWLEDGE-001/SPEC-WORKFLOW-KNOWLEDGE-001-20261003T213835Z-5121176c/`：定向 45 项通过（模块17、应用28），migration 37 项通过且 0 skip，harness 门禁通过。未跑全仓库/浏览器/生产部署。六项 P2 由后续 SPEC-WORKFLOW-KNOWLEDGE-002 处理。
