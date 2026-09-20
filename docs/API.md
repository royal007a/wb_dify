# Hify API 契约

## 1. 通用规则

- 前缀 `/api/v1`，资源名复数；非 CRUD 动作使用显式子资源或动作。
- JSON 字段使用 `camelCase`；数据库列使用 `snake_case`。
- 写请求支持 `Idempotency-Key`；错误有稳定 `code`，不把内部异常直接暴露给前端。
- 列表默认 cursor pagination；配置型小表可用 page/size，但 size 最大 100。
- 时间使用 RFC 3339 UTC；ID 对外使用不透明 UUIDv7/ULID 字符串。

当前已发布的 Run API 成功响应直接返回资源；新管理 API 使用 `Result<T>`。错误统一使用 `Result.fail(ErrorCode)`：

```json
{
  "code": 40000,
  "message": "参数错误",
  "data": null
}
```

## 2. Provider 与模型

```text
GET    /api/v1/providers
POST   /api/v1/providers
GET    /api/v1/providers/{providerId}
PUT    /api/v1/providers/{providerId}
DELETE /api/v1/providers/{providerId}
POST   /api/v1/providers/{providerId}/connection-tests
```

一期类型为 `OPENAI/ANTHROPIC/GEMINI/OPENAI_COMPATIBLE`；内部 `MOCK` 不能经管理 API 创建。模型目录随 Provider 聚合写入：`displayName` 只用于展示，`modelId` 才发送给供应商，且必须恰有一个启用的默认模型。

```json
{
  "name": "Team Gateway",
  "type": "OPENAI_COMPATIBLE",
  "baseUrl": "https://llm.example.com/v1",
  "enabled": true,
  "auth": {
    "credentialRef": "env:TEAM_LLM_KEY",
    "headerName": "Authorization",
    "prefix": "Bearer "
  },
  "models": [
    {"displayName": "Fast Model", "modelId": "model-fast", "enabled": true, "isDefault": true}
  ]
}
```

JSON 鉴权只保存版本化元数据和 `credentialRef`，不保存/回传原始凭证；响应只暴露 `credentialConfigured`。PUT 的 `auth` 缺省表示保留原配置，但切换 type 时必须同时提交新鉴权。连通性测试发起一次最小真实请求，返回 `success`、`latencyMs`、`providerCode` 和脱敏错误，并只更新独立 `provider_health` 行。

## 3. Agent

```text
GET    /api/v1/tools
GET    /api/v1/agents
POST   /api/v1/agents
GET    /api/v1/agents/{agentId}
PUT    /api/v1/agents/{agentId}
PUT    /api/v1/agents/{agentId}/tools
DELETE /api/v1/agents/{agentId}
POST   /api/v1/agents/{agentId}/publications
GET    /api/v1/agents/{agentId}/versions
```

`GET /api/v1/tools` 返回只读 Tool Catalog：稳定 `id`、展示名、描述、来源、风险等级和可用状态。Console 必须由该接口动态渲染可选工具，不得硬编码内置工具；MCP 接入后沿用同一契约扩展来源。

PUT 只更新 draft 基本信息，不修改工具。`PUT /tools` 以 `{"toolIds":[...]}` 全量替换草稿工具绑定，重复或未知工具返回参数错误。发布时将 draft 固化为不可变 `AgentVersion`，同时复制版本工具绑定并把工具集合纳入 digest。Conversation 始终绑定版本，不追随草稿变化；Run 返回 `agentVersionId/agentSnapshotDigest`。完整 tool schema snapshot 在 MCP 阶段补齐。

Agent 响应的 `hasUnpublishedChanges` 由当前运行配置 digest 与已发布 digest 比较得出：未发布或模型、指令、运行参数、工具等发生变化时为 `true`，再次发布同步后为 `false`。纯管理描述不影响运行快照。前端不得用 revision/version 数字猜测该状态。

DELETE 是归档而非物理删除：归档后不再列表展示，不能创建新 Conversation；草稿工具绑定被清理，发布版本和版本工具快照保留，旧 Conversation/Run 仍可重放。归档名称不允许复用。

## 4. Conversation 与 Run

```text
POST   /api/v1/conversations
GET    /api/v1/conversations
GET    /api/v1/conversations/{conversationId}
GET    /api/v1/conversations/{conversationId}/messages

POST   /api/v1/conversations/{conversationId}/runs
GET    /api/v1/runs/{runId}
POST   /api/v1/runs/{runId}/cancellations
GET    /api/v1/runs/{runId}/events
GET    /api/v1/runs/{runId}/events/stream
```

创建 Run：

```json
{
  "message": "现在几点，顺便计算 17*23"
}
```

当上一 Run 以 `NEEDS_INPUT` 结束时，客户端从 `continuation.decided` 保存开放的 `gapIds`，下一条消息显式恢复：

```json
{
  "message": "被除数是 42，除数是 7",
  "resume": {
    "runId": "run_waiting_for_input",
    "gapIds": ["gap_missing_calculator_args"]
  }
}
```

恢复不会让旧 Run 从终态回退；服务创建一个带 `resumedFromRunId/resolvedGapIds` 的新 Run。源 Run 必须属于同一 Conversation、状态为 `NEEDS_INPUT` 且存在可恢复 checkpoint。未知、已关闭或跨会话 Gap 返回参数错误。

创建 Run 必须提供 `Idempotency-Key`。服务先提交 user message 和 RUNNING run，再返回 `202 Accepted` 与 `runId`/stream URL。相同 `(conversationId, Idempotency-Key)` 只能创建一个 Run：第一次返回 `202`；请求体 checksum 相同的重复提交返回已有 Run、相同 stream URL 和 `200`；相同 key 但请求体不同返回 `409 IDEMPOTENCY_KEY_REUSED`。唯一约束与 user message/run/初始 event 在同一事务中写入。

Run 响应同时返回 `agentVersionId/agentSnapshotDigest/capabilityRevision/toolSchemaDigest`。前两项固定产品配置，后两项固定本轮实际暴露的工具定义；执行时不匹配会终止，不静默升级能力。

SSE 示例：

```text
id: 42
event: tool.call.completed
data: {"version":1,"runId":"run_...","toolCallId":"call_...","tool":"calculator","isError":false}
```

SSE 数据不包含供应商原始请求、API Key 或未裁剪 tool result。

真实 Provider 文本到达时逐块发送 `message.delta`；`model.completed` 表示该次模型流已闭合并可安全解析完整 tool call。流已经输出 delta 后不得自动重试整个请求。

工具失败有两种语义：

```text
# 可恢复：工具错误作为 observation 返回模型，Run 仍继续
event: tool.call.failed
data: {"version":1,"runId":"run_...","toolCallId":"call_...","recoverable":true,"errorCode":"TOOL_INPUT_INVALID"}

# 不可恢复：随后必须出现且只出现一个终态事件
event: run.failed
data: {"version":1,"runId":"run_...","terminalReason":"PERMISSION_DENIED","errorCode":"TOOL_PERMISSION_DENIED"}
```

受控 TAO 事件包括 `plan.created`、`step.try.started/completed/failed`、`continuation.decided`、`context.state.updated`、`replan.decided`、`recovery.narrated`、`confirmation.required`、`checkpoint.created/restored` 和 `run.input.accepted`。`continuation.decided.action` 只允许 `CONTINUE/FINISH/CLARIFY/RETRY/REPLAN/INTERRUPT`。`replan.decided` 明确给出 failure/root-cause/rollback/replan-start 四个位置；`context.state.updated` 给出 Evidence/Gap 快照版本与开放 Gap；`recovery.narrated` 把故障恢复原因投影为可审计事件。无安全替代时随后以 `run.needs_input` 结束本次流，等待用户通过新 Run 的 `resume` 输入恢复。当前没有写工具，因此 `confirmation.accepted/cancelled` 只是保留契约。

`history.committed` 表示某个 `operationId` 的 canonical history 已经持久化、回读并取得 revision；其 `semanticDigest` 用于识别合法重放和身份冲突。它不是外部工具副作用已提交的证明。

`context.prepared` 只在发生归档或压缩时发送，包含 `originalTokens/preparedTokens/archivedToolResults/compacted`。它描述模型输入投影，不表示 canonical history 被删除。

历史记忆提供只读审计/诊断 API：

```text
GET  /api/v1/runs/{runId}/memory
POST /api/v1/runs/{runId}/memory/search
GET  /api/v1/runs/{runId}/memory/details/{refId}
```

`memory` 返回该 Run 的结构化摘要和 Detail Catalog。`search` 请求为
`query/kind/from/to/entity/limit`，返回带分数的 ref 候选；它只是导航结果，不是事实证据。
`details` 必须与当前 Run 属于同一 Conversation，并从指定 canonical revision/message index 回读、校验 digest 后返回原文。运行时对应的模型工具名是 `history.search` 与 `history.detail`；前者打开未验证 Gap，后者成功后才形成 VERIFIED evidence。

前端不得把所有 `*.failed` 都当作 Run 终态；只有 `run.completed/run.failed/run.cancelled/run.needs_input` 结束流。

Playground 只列出 `enabled=true` 且已有 `publishedVersionId` 的 Agent。创建 Conversation 后，页面显示固定的
`agentVersionId`；SSE `message.delta` 必须追加到当前 assistant 消息，而不是每个 delta 新建一条消息。
客户端从 `streamUrl` 建立 EventSource，断线依靠事件 id 重连与服务端 replay，终态后再读取 Run 事实源收敛 UI。

## 5. Tools 与 MCP

### 5.1 意图路由预览

```text
POST /api/v1/intent-decisions
```

请求包含 `agentId` 与 `input`（最多 4000 字符），返回 `IntentDecision`：`intent/confidence/normalizedInput/slots/missingSlots/route/evidence/reason`。`route` 只允许 `unknown/clarify/tool/workflow`。这是评测和 shadow rollout 的预览接口，不执行工具或 Workflow，也不替代目标 runtime 的 schema、权限、预算和幂等校验。低置信、Top2 分差过小、缺少必填槽位或危险动作统一返回 `clarify`。

```text
GET    /api/v1/tools
GET    /api/v1/tool-definitions/{toolName}
POST   /api/v1/tools/{toolName}/dry-runs

GET    /api/v1/mcp-servers
POST   /api/v1/mcp-servers
PATCH  /api/v1/mcp-servers/{serverId}
DELETE /api/v1/mcp-servers/{serverId}
POST   /api/v1/mcp-servers/{serverId}/connection-tests
POST   /api/v1/mcp-servers/{serverId}/tool-refreshes
```

MCP Server 保存 URL、transport、credentialRef、allow policy 和最近一次工具 schema snapshot。Agent 绑定具体 tool identity + schema version。

## 6. Knowledge（P1）

```text
GET/POST /api/v1/knowledge-bases
POST     /api/v1/knowledge-bases/{id}/documents
GET      /api/v1/documents/{documentId}
DELETE   /api/v1/documents/{documentId}
POST     /api/v1/knowledge-bases/{id}/retrieval-tests
```

上传接受 TXT/Markdown，限制单文件大小、总量和 MIME。索引异步语义在单实例内可使用受控 executor，但任务和进度必须持久化；应用重启后可从数据库恢复待处理任务。

## 7. Workflow（P1）

```text
GET/POST /api/v1/workflows
PATCH    /api/v1/workflows/{id}/draft
POST     /api/v1/workflows/{id}/validations
POST     /api/v1/workflows/{id}/versions
POST     /api/v1/workflow-versions/{id}/runs
```

Workflow DSL 使用显式 `schemaVersion`。发布前验证入口/终点、节点 ID 唯一、边可达、Condition 默认分支、无循环和引用资源版本存在。

## 8. 稳定错误码

| 范围 | 示例 |
|---|---|
| 通用 | `VALIDATION_FAILED`、`NOT_FOUND`、`CONFLICT`、`RATE_LIMITED` |
| Provider | `PROVIDER_AUTH_FAILED`、`PROVIDER_TIMEOUT`、`MODEL_NOT_AVAILABLE` |
| Run | `RUN_ALREADY_TERMINAL`、`RUN_CANCELLED`、`BUDGET_EXCEEDED` |
| Tool/MCP | `TOOL_NOT_FOUND`、`TOOL_INPUT_INVALID`、`TOOL_PERMISSION_DENIED`、`MCP_UNREACHABLE` |
| Knowledge | `DOCUMENT_TYPE_UNSUPPORTED`、`INDEXING_FAILED` |
| Workflow | `WORKFLOW_INVALID`、`NODE_FAILED` |
