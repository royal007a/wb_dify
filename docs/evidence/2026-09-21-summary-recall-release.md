# 摘要、DetailRef 与迭代召回发布证据

日期：2026-09-21  
代码提交：`7bb703138a6dc2ccb23f51832f6aebea94997c98`  
回滚提交：`638457d444d36e903496023ac4263ada4e77ca11`

## 发布前门禁

- Harness scopes：`backend,migration,runtime,eval,harness` 全通过。
- PostgreSQL Testcontainers 使用 `pgvector/pgvector:pg16`，V1→V13 实迁移未跳过。
- 验证 GIN `idx_detail_ref_fts`、cosine HNSW `idx_detail_ref_embedding_hnsw` 与
  `POSTGRES_FTS_PGVECTOR_RRF` 查询策略。
- 召回集：12 例；literal lexical Top1 50%，hybrid Top1 100%，Precision@3 33.33%，
  本次门禁 ranking P95 5.422 ms，预算 50 ms。完整限制见 `recall-memory-v1.json`。
- Harness evidence：
  `harness/evidence/RECALL-002/RECALL-002-20260920T160349Z-fe885bf9/verification.json`。

## 本地发布与 smoke

- URL：`http://localhost:8088/`
- `/api/v1/health`：HTTP 200，`Hify is running`。
- PostgreSQL：Flyway v13 success；`vector` 扩展、GIN 与 HNSW 索引存在。
- 四轮 Conversation `8a744657-0266-4037-84b5-793207fa6514`，最终 Run
  `53f9b4cd-d1f9-4308-85ca-170e724f4454` 为 COMPLETED。
- Memory API：1 个 CURRENT checkpoint summary、11 个 DetailRef；search 返回 5 个 RRF 候选；
  detail 回读 canonical 原文且包含 `mint-42`。

## 远端发布与 smoke

- URL：`https://118.196.123.132/hify/`（当前为自签名 TLS）。
- 主机仓库 HEAD 与运行 jar：`7bb703138a6dc2ccb23f51832f6aebea94997c98`。
- 前端与 `/hify/api/v1/health`：HTTP 200。
- PostgreSQL 16.15：宿主机安装 `postgresql-16-pgvector`，DBA 预创建 `vector` 扩展；
  Flyway v13 success，GIN/HNSW 索引存在。应用账号仍非 superuser。
- 四轮 Conversation `57817824-4655-45c5-8e60-17c474aa53ab`，最终 Run
  `31551275-b897-4647-aa2a-ba8a6d12c824` 为 COMPLETED。
- Memory API：1 个 CURRENT summary、11 个 DetailRef；search 策略为
  `POSTGRES_FTS_PGVECTOR_RRF`、返回 5 个候选；detail 回读包含 `remote-mint-42`。
- Playwright 用真实页面完成发布 Agent → calculator → SSE → COMPLETED，1/1 passed；截图保存在
  `harness/evidence/DEPLOY-001/DEPLOY-001-20260920T161559Z-6463d37d/remote-browser.png`。

## 回滚与边界

- 旧 jar 和前端 dist 已保存在远端
  `/opt/hify/data/releases/638457d-before-memory/`；代码可切回上述回滚提交后恢复静态资源并重启。
- V11-V13 仅新增表、列、扩展和派生索引；回滚应用时保留这些对象，不执行 destructive down migration。
- pgvector/FTS/摘要都是 canonical history 的派生表示。回滚或重建索引不会改变 Run canonical history。
- 当前 64 维本地 embedding 是零成本 bootstrap adapter；真实生产 embedding 模型、版本化回填和价格计量仍是后续工作。
