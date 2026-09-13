# Hify 初版交付与验证证据

日期：2026-09-12。范围：一个可部署的本地纵向闭环，不等同于完整 MVP。本文件保留首次纵向闭环的历史证据；同日完成的多模块地基与并发加固见 `BACKEND_FOUNDATION.md`。

## 已交付

- Vue 3 + TypeScript Playground，可选择 Demo Agent、创建会话、发送消息、查看工具事件、Run 终态并请求取消。
- Spring MVC API 与 SseEmitter；移除 WebFlux 编程模型，OpenAI-compatible adapter 改用 RestClient。
- 持久化 Conversation、Message、AgentRun、RunEvent；`(conversation_id, idempotency_key)` 唯一约束。
- Query Loop：原生结构化 tool calls、call/result id 配对、maxTurns、overall deadline、取消检查、工具预算、估算 token 预算和明确终态。
- ToolRuntime：allow-list、read-only permission、必填/字符串参数校验、可恢复错误和 32 KiB 结果上限。
- Flyway `V1__baseline.sql`，PostgreSQL 16 部署；Nginx 关闭 API/SSE buffering。
- `deploy/up.sh` / `deploy/down.sh`；同时保留 `compose.yaml`，但本机 Docker 未安装 Compose plugin。

## 自动验证

```text
Apache Maven 3.9.16 / Java 17.0.19
mvn test: 8 tests, 0 failures, 0 errors
npm run build: Vue/Vite production build passed
Flyway: PostgreSQL schema migrated to v1
GET /actuator/health: UP
Mock Run: COMPLETED, answer contains 391, turns=2, toolCalls=1
Persisted events: run.created → model/tool events → message.delta → run.completed
SSE terminal replay: 1305 bytes received through Nginx
Idempotent replay: same key/body returned HTTP 200 and the original Run
Idempotency misuse: same key/different body returned HTTP 409
App restart: completed Run remained readable from PostgreSQL
Nginx SSE response: X-Accel-Buffering=no verified
```

最终验收 Run ID：`858da6f8-8825-4989-8ede-c84bbb5dafed`，包含 10 个持久化事件，SSE replay 为 1314 bytes。这是本机开发数据，仅用于证明端到端链路。

## 运行

```bash
cd /Users/weberzhao/hify
./deploy/up.sh
open http://localhost:8088
```

停止容器但保留 PostgreSQL volume：

```bash
./deploy/down.sh
```

## 明确未完成

- 完整 api/application/domain/infrastructure 包级隔离与自动架构测试（Maven 多模块已经完成）。
- 真正 token streaming、Provider 调用级取消、精确 token/cost、bulkhead/熔断/分类重试。
- Agent 草稿/不可变版本/发布、MCP、RAG、Workflow、认证与生产级凭证。
- Redis replay、多副本、对象存储、pgvector/HNSW、备份恢复和容量测试。

这些缺口继续以 `IMPLEMENTATION_PLAN.md` 和 `PHASE_0_ALIGNMENT.md` 为准，不因为初版可以运行而视为完成。
