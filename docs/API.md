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
PATCH  /api/v1/providers/{providerId}
DELETE /api/v1/providers/{providerId}
POST   /api/v1/providers/{providerId}/connection-tests

GET    /api/v1/models
POST   /api/v1/models
PATCH  /api/v1/models/{modelId}
```

Provider 请求只接收 `credentialRef`，不回传原始凭证。连通性测试返回 `success`、`latencyMs`、`providerCode` 和脱敏错误。

## 3. Agent

```text
GET    /api/v1/agents
POST   /api/v1/agents
GET    /api/v1/agents/{agentId}
PATCH  /api/v1/agents/{agentId}/draft
POST   /api/v1/agents/{agentId}/validations
POST   /api/v1/agents/{agentId}/versions
POST   /api/v1/agents/{agentId}/publications
GET    /api/v1/agents/{agentId}/versions
```

发布时将 draft 固化为不可变 `AgentVersion`，内容含 model snapshot、prompt、参数、tool schema snapshot、预算和 fallback 策略。Conversation 始终绑定版本，不追随草稿变化。

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

SSE 示例：

```text
id: 42
event: tool.call.completed
data: {"version":1,"runId":"run_...","toolCallId":"call_...","tool":"calculator","isError":false}
```

SSE 数据不包含供应商原始请求、API Key 或未裁剪 tool result。

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

前端不得把所有 `*.failed` 都当作 Run 终态；只有 `run.completed/run.failed/run.cancelled/run.needs_input` 结束流。

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
