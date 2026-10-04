# Hify API 契约

完整的当前接口清单与验收边界见 [可执行测试规格](spec/README.md)；68个显式 `/api` 方法/路径（含只读提交查询）由 `ApiContractInventoryTest` 与真实 Spring 注册映射双向核对。下文不把规划接口列为已开放。

## 1. 通用规则

- 前缀 `/api/v1`，资源名复数；非 CRUD 动作使用显式子资源或动作。
- JSON 字段使用 `camelCase`；数据库列使用 `snake_case`。
- 仅创建 Run 强制支持 `Idempotency-Key`；其他写请求目前不承诺幂等键。错误通过 `Result.fail(ErrorCode)` 返回，协议边界完整性见审计 A05。
- 分页管理表使用 page/pageSize；响应 size/total/page 在顶层。MCP 和历史事件部分接口直接返回完整列表，没有 cursor pagination。
- 分页越界目前不统一：Knowledge 返回400，Workflow将page夹到至少1、pageSize夹到1..100。这是现有兼容行为记录，不是原规格已经作出的统一产品决策；后续统一需单独决定并说明兼容影响。
- Instant 时间为 UTC ISO 格式；DemoItem 的 LocalDateTime 为无时区 ISO。大多数 ID 是 UUID 字符串，DemoItem 为 Long，不承诺 UUIDv7/ULID。

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

env/system 引用默认拒绝：管理员必须先配置 `HIFY_CREDENTIAL_REFERENCE_BINDINGS`，将引用绑定到精确 baseUrl。创建、更新（包括保留鉴权但修改 URL）和运行期均检查，旧数据不豁免；禁止把应用主密钥或数据库密码当供应商密钥引用。示例引用不代表默认已获批准。

## 3. Agent

```text
GET    /api/v1/tools
GET    /api/v1/agents
POST   /api/v1/agents
GET    /api/v1/agents/{agentId}
PUT    /api/v1/agents/{agentId}
PUT    /api/v1/agents/{agentId}/tools
PUT    /api/v1/agents/{agentId}/knowledge-bindings
PUT/DELETE /api/v1/agents/{agentId}/workflow-binding
PUT    /api/v1/agents/{agentId}/mcp-bindings
DELETE /api/v1/agents/{agentId}
POST   /api/v1/agents/{agentId}/publications
GET    /api/v1/agents/{agentId}/versions
```

`GET /api/v1/tools` 返回只读 Tool Catalog：稳定 `id`、展示名、描述、来源、风险等级和可用状态。Console 必须由该接口动态渲染可选工具，不得硬编码内置工具；MCP 接入后沿用同一契约扩展来源。

PUT 只更新 draft 基本信息，不修改能力绑定。`PUT /tools`、`PUT /knowledge-bindings`、`PUT /workflow-binding`、`PUT /mcp-bindings` 分别替换草稿能力。发布时将 draft 固化为不可变 `AgentVersion`：Workflow 固定 `workflowVersionId/checksum`，MCP 固定 `serverRevision/serverSchemaDigest/tool schema digest`，且一期只接受 READ。Conversation 始终绑定版本，不追随草稿变化；Run 返回 `agentVersionId/agentSnapshotDigest`。

Workflow绑定执行还须比对Agent固定checksum与同一加载版本的checksum，再校验原始DSL；缺失/不符时父Run为FAILED/WORKFLOW_ERROR，不创建Workflow执行行或助手消息。直接版本试跑不具有Agent固定摘要，仍按版本自身完整性检查。详见`spec/SPEC_KNOWLEDGE_INTEGRITY.md`；不承诺防御同时篡改两份数据库记录。

Agent 响应的 `hasUnpublishedChanges` 由当前运行配置 digest 与已发布 digest 比较得出：未发布或模型、指令、运行参数、工具等发生变化时为 `true`，再次发布同步后为 `false`。纯管理描述不影响运行快照。前端不得用 revision/version 数字猜测该状态。

DELETE 是归档而非物理删除：归档后不再列表展示，不能创建新 Conversation；草稿工具绑定被清理，发布版本和版本工具快照保留，旧 Conversation/Run 仍可重放。归档名称不允许复用。

## 4. Conversation 与 Run

```text
POST   /api/v1/conversations
GET    /api/conversations/{id}

POST   /api/v1/conversations/{conversationId}/runs
GET    /api/v1/conversations/{conversationId}/runs/by-key
GET    /api/v1/runs/{runId}
POST   /api/v1/runs/{runId}/cancellations
GET    /api/v1/runs/{runId}/events
GET    /api/v1/runs/{runId}/events/stream
```

当前无会话列表 API，也无 v1 会话详情/独立消息列表。旧 `/api/conversations/{id}` 一次返回 conversation 与 messages。旧 `/api/tools` 返回两种基础工具定义；新的 `/api/v1/tools` 为四种内置工具目录。

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

Run输入上限为20000个Java UTF-16代码单元（与Bean Validation/String.length一致，不是UTF-8字节或Unicode码点数）；超过上限在写入前返回400/40000，恰好20000可接受。仅SQLState=23505且约束为uq_run_idempotency（H2为该约束的生成索引）才作为并发幂等重放；其他完整性异常回滚后，如会话已不存在返回404/40400，否则固定500/50000，不回显SQL/约束/输入。现有普通会话不存在入口的400兼容行为不在本片统一变更。

Run创建的message、conversationId、Idempotency-Key、resume.runId/gapIds含NUL（U+0000）时，数据库访问前拒绝400/40000。PG唯一冲突读取驱动结构化SQLState/constraint字段，不依赖lc_messages；存在PG诊断时优先于Hibernate的报文解析结果，缺字段不猜测。H2兼容路径保持原行为。

Console在一次此前非unknown的resume提交被明确4xx拒绝后，清除恢复身份、暂停该会话继续发送，提示显式新建会话并重新描述完整任务；不自动恢复该次澄清回答到输入框，不让失效resume反复提交或悄悄变成新任务。若此前结果不明，仍保留原key查找/重放，不能用后来的4xx武断丢弃已提交身份。

取消接口对已存在的终态Run仍返回202及原资源，不修改状态、取消时间戳或追加事件；COMPLETED/FAILED且原本未请求取消时，cancelRequestedAt保持null。这同样是当前HTTP兼容口径的显式记录，并非宣称初版规格已预先规定；不存在的Run仍为404。

Console按一次逻辑提交保留message/resume/Idempotency-Key；响应结果不明时显式同key重试，创建中取消不会误发给上一Run。未知提交的取消只用GET by-key找身份，不再创建Run；找不到不能宣称取消成功。允许明确“放弃等待（不取消服务端）”，解除页面等待但不自动重发，后台可能继续；带resume时不自动恢复失去上下文的澄清文本。创建会话和Run各有10秒客户端请求期限，不表示服务端取消。短断线暂停后的人工同步可为同一Run重连SSE，保留事件去重，不重发POST。非澄清终态清除resume，NEEDS_INPUT按持久事件取Gap；缺Gap时提示新建会话，不无限同步或暗中重发。页面内状态及跨页面/多标签边界见 `spec/SPEC_CHAT_LIFECYCLE.md`。

创建 Run 必须提供 `Idempotency-Key`。服务先提交 user message 和 RUNNING run，再尝试调度，返回 `202 Accepted` 与 `runId`/stream URL。相同 `(conversationId, Idempotency-Key)` 只能创建一个 Run：第一次返回 `202`；请求体 checksum 相同的重复提交返回已有 Run、相同 stream URL 和 `200`；相同 key 但请求体不同返回 `409 IDEMPOTENCY_KEY_REUSED`。唯一约束与 user message/run 在同一事务中写入，初始 event 是随后独立事务，不宣称与创建整体原子。

已有记录的同key重放先于可变的Provider/Agent准入检查，停用Provider后仍能找回原结果；异体仍40901，未命中的新工作仍按原规则校验。`GET .../runs/by-key`同样携带`Idempotency-Key`（非空白且最多128字符），仅查这个会话的持久记录：200返回RunView，404表示此刻未找到，400表示key缺失/非法。成功与未命中均`Cache-Control: no-store`，按`Idempotency-Key`变体区分；不写消息/事件、不提交任务、不以Provider启用状态阻止回读。404不证明先前POST不会晚到，也不等于取消成功。没有持久化的取消墓碑。未来引入用户权限时，查找与重放必须与新建同样授权，不能以幂等为由绕过权限。

若本地 Run 执行器因容量拒绝调度，已创建的 Run 收敛为 `FAILED / EXECUTOR_REJECTED`，与 `run.failed` 终态事件一起提交，不保存 assistant 消息，也不返回 JDK 线程池描述。首次请求仍是202，但 body 已为终态；同 key 重放200返回同一失败结果，不会重新调度或重复用户消息。需重新执行时使用新 key。持久化取消先提交时仍优先成为 `CANCELLED`；启动恢复中单条容量拒绝或单条数据库操作失败不会阻止其余 Run 继续收敛，数据库操作失败的行保持未结算，需后续启动恢复，不假报成功。

应用上下文关闭与用户取消分开：关闭后不再调度新工作，关闭造成的拒绝不是容量失败。明确被关闭中断、且未持久化用户取消的执行保留 `RUNNING`，可写库时追加非终态事件 `run.interrupted`（`reason=APPLICATION_SHUTDOWN,recoverable=true`），不伪造终态。已经计算出的终态仍正常提交；COMPLETED 可保存助手消息，不因 stopping 本身丢弃结果。下次启动只尝试接续未终态 Run 的 checkpoint；不是新用户输入或重放 HTTP 请求。事件只表示具备启动恢复资格，不保证恢复必定成功。已提交的用户取消仍优先成为 CANCELLED。

Workflow 的一次执行/在途节点用 `INTERRUPTED` 记录进程中断，与 CANCELLED/TIMED_OUT 区分；重启只收敛旧进程留下的 RUNNING 执行，不改启动期间新接入的执行。父 AgentRun 的恢复会从同一不可变 WorkflowVersion 创建新的 Workflow 执行记录，不从任意节点断点继续，也不回滚已发生的外部操作。数据库不可用、kill -9 或执行器无法在关闭等待期限内退出时，中断事件可能缺失，原 RUNNING 行仍是下次恢复依据。详见 `docs/spec/SPEC_RUN_SHUTDOWN.md`。

同一实例内 create 与启动恢复共用每个 Run 的调度所有权；重复扫描不会再次提交、拒绝或清理已有 owner 的状态。晚到的 create/恢复先读当前行，终态不再执行。worker 前复查持久取消，cancel 在 owner 清理之后返回不会重新生成本地标志。这是进程内互斥，不是数据库执行租约，不提供多副本或滚动重叠执行的 fencing 保证。

Run 响应同时返回 `agentVersionId/agentSnapshotDigest/capabilityRevision/toolSchemaDigest`。前两项固定产品配置，后两项固定本轮实际暴露的工具定义；执行时不匹配会终止，不静默升级能力。

SSE 示例：

```text
id: 42
event: tool.call.completed
data: {"version":1,"runId":"run_...","toolCallId":"call_...","tool":"calculator","isError":false}
```

SSE 数据不包含供应商原始请求、API Key 或未裁剪 tool result。

SSE 游标约定：`Last-Event-ID` 缺省或 `0` 从头 replay；正数必须是**本 Run 的已提交事件 ID**，其他 Run、未知或负数返回 HTTP 400 / `PARAM_ERROR`（JSON），不能跳过终态。订阅先发不带 ID 的 heartbeat，再按 ID 递增发送已提交事件；已消费终态游标会正常关闭且不重复发送终态。

SSE 背压：每个订阅由独立的串行发送 worker 推进，提交回调和心跳调度线程仅唤醒，不做网络 IO。每次读取最多 32 条事件，数据库调用结束后再发送，无逐事件内存队列；默认最多 64 个订阅，满时新订阅 HTTP 503 / `SERVICE_UNAVAILABLE`，不影响 Run 落库。客户端保留最后已收到的 ID 重连；断开 SSE 不等于取消 Run。180s 流重连周期保持不变。Tomcat socket 不活动写超时默认 10s（`HIFY_HTTP_CONNECTION_TIMEOUT`，也影响请求读取），用于回收不读数据的客户端；这不是持续缓慢传输的全程硬超时。部署端代理也需配置相应超时和连接限制。

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

checkpoint 落后时，恢复先按 `model:N/tool:callId` 查已提交操作并验证摘要和完整前缀；不重新调用模型生成已提交响应，也不重复发送其 delta。V23 为新工具结果同行保存类型、计划、Evidence/Gap 与计数；重放这些工具结果不再次执行工具。旧工具记录缺少该恢复状态时 fail-closed 为 `HISTORY_COMMIT_FAILED`，不能自动重执行或虚构证据。未提交 READ、部分流式响应和跨重启总预算仍有限制，见 `docs/spec/SPEC_HISTORY_RECOVERY.md`。

`context.prepared` 只在发生归档或压缩时发送，包含 `originalTokens/preparedTokens/archivedToolResults/compacted`。它描述模型输入投影，不表示 canonical history 被删除。

历史记忆提供只读审计/诊断 API：

```text
GET  /api/v1/runs/{runId}/memory
POST /api/v1/runs/{runId}/memory/search
GET  /api/v1/runs/{runId}/memory/details/{refId}
```

普通聊天的 `memory` 返回该 Run 的结构化摘要和 Detail Catalog。知识门禁 Run 的原始 memory 不开放：目录/摘要为空，search 过滤这类来源 Run（含同会话旧索引），detail 返回 409/CONFLICT；已完成答案仍通过 Run 和聊天消息读取。该限制不删除内部恢复历史。`search` 请求为
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

GET    /api/v1/mcp-servers
POST   /api/v1/mcp-servers
PUT    /api/v1/mcp-servers/{serverId}
DELETE /api/v1/mcp-servers/{serverId}
POST   /api/v1/mcp-servers/{serverId}/tools:refresh
```

MCP Server 保存 URL、transport、credentialRef、enabled 和最近一次工具 schema snapshot。Agent 绑定具体 tool identity + schema version。独立 tool-definitions/dry-runs 尚未提供；内置工具通过 Run 执行，MCP READ 调试见第 8 节。

## 6. Knowledge（P1）

```text
GET/POST /api/v1/knowledge-bases
GET/PUT/DELETE /api/v1/knowledge-bases/{id}
POST     /api/v1/knowledge-bases/{id}/documents
GET      /api/v1/knowledge-bases/{id}/documents
GET      /api/v1/documents/{documentId}
GET      /api/v1/documents/{documentId}/chunks
DELETE   /api/v1/documents/{documentId}
POST     /api/v1/knowledge-bases/{id}/retrieval-tests
```

上传接受 TXT/Markdown（目前按扩展名和UTF-8内容验证，不保证MIME嗅探）。单文件上限10MiB（10×1024×1024字节），整个multipart请求上限12MiB（含字段/边界）；超限413/41300。不存在跨请求累计配额，不能把单请求上限称为总存储配额。索引异步语义在单实例内可使用受控 executor，但任务和进度必须持久化；应用重启后可从数据库恢复待处理任务。检索返回 `chunkId/documentId/documentVersion/content/digest/score/rank`，客户端引用必须保存这些字段，不能只保存展示文本。

绑定知识的 Chat 在零候选/任一来源失败时不调用模型，分别 FAILED / KNOWLEDGE_NO_EVIDENCE、KNOWLEDGE_RETRIEVAL_FAILED。候选不是已验证答案；最终至少有一个合法 `[K数字]` 且所有所引 canonical chunk/digest 校验成功，才通过来源完整性门禁，否则 NEEDS_INPUT 并保存 Gap。恢复沿用原 K 映射，不重新检索编号。该路径不发送正文 delta，成功终态后回读 Run；普通聊天不变。完成事件的 `answerVerification=SOURCE_REFERENCES_ONLY,semanticClaimsVerified=false` 仅表示引用来源完整，不保证答案属实。向量候选过滤默认 0.5（HIFY_KNOWLEDGE_MIN_VECTOR_SCORE），不作为充分性判定；关键词仍独立召回。Workflow KNOWLEDGE 零候选失败，不继续 END。详细边界见 `spec/SPEC_KNOWLEDGE_FINISH.md`。

## 7. Workflow（P1）

```text
GET/POST /api/v1/workflows
GET/PUT/DELETE /api/v1/workflows/{id}
POST     /api/v1/workflows/{id}/validations
POST     /api/v1/workflows/{id}/versions
GET      /api/v1/workflows/{id}/versions
GET      /api/v1/workflow-versions/{id}
POST     /api/v1/workflow-versions/{id}/runs
GET      /api/v1/workflow-runs/{id}
```

Workflow DSL 使用显式 `schemaVersion`。保存、发布和执行前共用结构校验：恰有一个START（无入边）、至少一个END（无出边）、全图可达无环；其他非条件节点恰有一条无条件出边，因此每条可走路径都终止于END。CONDITION恰有一个默认分支，其他标签只接受不重复的true/false。非法图返回参数错误，已存储的非法旧版本也在执行前拒绝，不改写其DSL/checksum。

最长结构路径不得超过50步（含START/END），不是全图节点总数限制。CONDITION支持true/false、单布尔模板变量或单个contains/==/!=比较；含运算符的字面量必须加引号，解析发生在模板插值之前。配置中不支持字面量`{{`/`}}`。旧非法定义的Chat终态为WORKFLOW_ERROR。完整语法、兼容边界与用例见 `spec/SPEC_WORKFLOW_GRAPH.md`。

节点/边key、输出变量名为1–128个字母、数字、下划线或连字符，不隐式裁剪空格。模板使用 `{{nodeKey.variable}}`（允许括号内首尾空格），只能引用严格必经上游已声明的变量。START输出为实际START key的userMessage；TEMPLATE/CONDITION默认result、KNOWLEDGE默认citations，可用outputVariable覆盖；END没有输出变量。运行缺值直接失败；插入的用户/工具文本只作为数据，不能二次展开为模板。结构安全不等于知识相关性或 FinishGate 已贯通，这些仍见 `spec/AUDIT_FINDINGS.md`。

KNOWLEDGE 发布时在 config 中生成只读 `knowledgeSnapshot={corpusVersionId,manifestDigest,revisionNo,chunkCount}`，顶层另写服务端 `publication.knowledgeSnapshotFormat=1`，两者连同 knowledgeBaseId 固定到 DSL/checksum。草稿不能提交 config 保留字段（400），顶层发布标记不属于客户端 Draft DTO。旧未冻结/无标记 KNOWLEDGE 版本在 Agent 绑定/发布及直接执行时返回409；需依次重新发布 Workflow、Agent，并创建新会话，不重写旧版本。执行前校验整个 DSL checksum；纯模板/条件旧图 checksum/结构合法则不受影响。冻结运行验证清单及原文摘要，文档/库归档不改变历史语料；向量重建可能改变排序/topK。归档不是撤销历史访问或数据擦除。canonical application port 仅在提供准确摘要且有冻结成员引用时允许回读归档分块，没有新增 HTTP 回读路由。详见 `spec/SPEC_WORKFLOW_KNOWLEDGE.md`。

试跑接口目前同步执行后返回 HTTP202，不能据此宣称后台异步队列。

Workflow/Node执行状态为RUNNING、SUCCEEDED、FAILED、CANCELLED、TIMED_OUT（V21兼容迁移扩大CHECK约束）。Chat进入Workflow时传入从Run创建时间扣减后的同一预算与取消控制；独立试跑默认60秒（hify.workflow.timeout）。节点前后复查，排队时间计入预算；只读阻塞任务用有界workflowIoExecutor，停止等待时取消Future，协作worker接受中断。取消/超时不启动后续节点，不能保存为助手成功回答。工作流不再跨阻塞IO持有整体数据库事务，节点记录逐步提交，这不意味着外部副作用可回滚。

工作流停止投影为workflow.cancelled/workflow.timed_out；Run对应run.cancelled或run.failed（state=TIMED_OUT、terminalReason=TIMEOUT）。Run终态提交锁行读取持久cancelRequestedAt：先落库的取消优先，先提交的终态不能被后来的取消改写。CPU/SQL驱动不响应中断时，Future取消不等于底层工作已停止；真实数据库取消与SSE事务原子性需各自的验证证据。

Agent 一期最多绑定一个入口 Workflow。发布 Agent 时固定当前 published WorkflowVersion；Chat 创建 Run 后若该固定版本存在，进入确定性 Workflow executor 并发出 `workflow.started` 及执行结果投影，否则进入 QueryLoop。Console 画布和 Runtime 读写同一 DSL，并提供节点、连线、属性、校验、试跑和发布版本 diff。

Workflow 结果投影为 `workflow.completed/failed/cancelled/timed_out/interrupted`，新写入 payload 的 `version=2`，包含 `workflowRunId/workflowVersionId/workflowChecksum/state/executionState/runState/runTerminalReason/assistantCommitted`。`state` 与 `executionState` 均指 Workflow 执行事实，不是父 Run 交付状态。例如 `workflow.completed` 可以同时携带 `executionState=SUCCEEDED,runState=CANCELLED,assistantCommitted=false`：计算已完成但交付被取消。前端完成判断必须用 `run.*` 终态或回读 Run，不能仅见 `workflow.completed` 就显示回复成功。历史 v1 事件不改写。

结果投影、父终态、助手消息（仅 COMPLETED）、父终态事件在同一事务提交。关闭挂起时投影 `workflow.interrupted` 与 `run.interrupted` 同事务，父 Run 仍为 RUNNING、`runTerminalReason` 为空；真正取消在父行锁下仍优先。Workflow 真实失败映射 `FAILED/WORKFLOW_ERROR`，执行前已经过期而没有 Workflow 执行记录时不伪造结果投影。详见 `docs/spec/SPEC_WORKFLOW_SETTLEMENT.md`。

## 8. MCP Server 与调试

```text
GET/POST /api/v1/mcp-servers
GET/PUT/DELETE /api/v1/mcp-servers/{id}
POST     /api/v1/mcp-servers/{id}/tools:refresh
GET      /api/v1/mcp-servers/{id}/tools
POST     /api/v1/mcp-servers/{id}/tools/{toolName}:call
```

`tools:refresh` 调 `tools/list`，以 server revision 保存完整 schema 和 digest；不会修改已固定的 AgentVersion/Run 快照。`tools/{toolName}:call` 只能调用已发现且 risk=READ 的工具，请求包含 arguments；响应包含 callId、toolName、serverRevision、result、error、elapsedMs、schemaDigest。其他风险等级返回 FORBIDDEN。

Console 的「编辑」回填 name、endpointUrl、enabled，用 PUT 更新原 id，不归档重建。凭据默认 KEEP，不回填 Token。

POST/PUT 新增 `credentialAction`：

| 操作 | 输入 | 语义 |
|---|---|---|
| KEEP | 无 Token/ref | 保持当前凭据，新建时为无鉴权 |
| TOKEN | credentialToken | 直接输入 Bearer Token 原文（最多 8192，无前缀/空白），后端加密并生成新引用 |
| REFERENCE | credentialRef | `env:变量名` 或 `system:属性名`；变量值与精确 endpoint 的授权绑定均需由管理员配置 |
| CLEAR | 无 Token/ref | 清除草稿鉴权，不撤销历史版本 |

禁止混合提交或客户端指定 stored 引用。旧请求省略 action 时：ref 缺省保持、空串清除、非空更新 env/system 引用。Token 必须显式 TOKEN，不可绕过动作选择。

有凭据时修改 endpoint 禁止 KEEP（包括旧客户端省略 action/ref）；必须重新提交 TOKEN/REFERENCE 或显式 CLEAR，否则400且原配置不变。REFERENCE 默认拒绝，保存与每次网络调用前均验证管理员配置的“引用→精确endpoint”；端口和路径必须匹配，不允许任意环境变量/系统属性、不支持通配授权。历史发布也受当前安全策略约束，不改写历史快照。直接 TOKEN 和无鉴权模式不需要引用白名单。

响应增加 `credentialMode=NONE/TOKEN/REFERENCE/UNAVAILABLE` 与 `credentialConfigured`。仅 REFERENCE 可回传合法变量引用；TOKEN 不返回原文、密文或存储 ID。configured 表示配置存在，不代表远端鉴权成功。畸形 JSON 和参数错误不回显密钥。

直接 Token 存储需一次性配置 `HIFY_MCP_MASTER_KEY`（Base64 32 字节）；缺少配置返回 CONFLICT，env/system 模式不受影响。之后页面更换 Token 不需要重启。

改名或启停不改变已发现 schema。修改 endpointUrl/credentialRef 则将草稿置 NEW、清除当前 digest/错误，保留历史 revision；重新发现成功前不得调试或发布新绑定。旧 AgentVersion/Run 继续使用冻结的 endpoint/credentialRef/tool schema，这不是撤销旧版本权限的开关。

Agent 发布时把 MCP 工具映射成稳定 runtime tool name 和 `ToolDefinition`；QueryLoop 只接收该 AgentVersion 冻结的 definitions。网络调用前重新校验 run/attempt lease、capabilityRevision 与 cancel；endpoint 和 credential reference 来自固定 server revision，不读取可变 Server 草稿。

## 9. 稳定错误码

| 范围 | 示例 |
|---|---|
| 成功 | 200 / HTTP200（创建时按接口使用201/202） |
| 参数/权限 | 40000 / HTTP400，40100 / HTTP401，40300 / HTTP403 |
| 不存在/冲突 | 40400 / HTTP404，40900 / HTTP409 |
| HTTP 协议错误 | 40500 / HTTP405（保留 Allow），40600 / HTTP406，41500 / HTTP415 |
| Multipart 解析 | 41300 / HTTP413（容器上传上限），40000 / HTTP400（畸形请求）；不回显文件名、boundary、正文或异常堆栈 |
| 幂等冲突 | 40901 / HTTP409 |
| 系统 | 50000 / HTTP500 |

以上为 `ErrorCode` 数字枚举。Provider 失败分类、Run terminalReason 和 Tool errorCode 属于结果/事件字段，不应与 HTTP 错误码混称。未知Run的详情、事件、SSE订阅和取消均返回40400/HTTP404（SSE尚未建立时为application/json），未知路由404不反射请求路径。响应已提交的SSE无法再改成JSON错误。

Multipart解析先于路由/媒体类型解析：畸形multipart发往不存在路由或非上传接口也可能先返回400/413，不能无条件期望404/415。文件/请求大小超限统一413；类型/空内容/非法UTF-8等仍400。知识服务内的大小检查是绕过Servlet直接调用时的防御，同样413；不再将它宣称为正常HTTP可达的“>10MB返回400”。两份仓库nginx配置在Hify API入口提供固定JSON 413；必须发布相应代理配置才生效。任意巨大/慢请求、磁盘故障、连接已重置或容器在DispatcherServlet前拒绝等情形，不保证客户端能读到JSON（Tomcat有限吞包16MiB）。

Memory和部分旧会话/resume入口仍有IllegalArgumentException映射400的历史边界，且Memory未知Run与resume未知runId仍可能回显该标识；不能泛称全部资源已统一404或所有错误出口已无输入回显。容器默认/error的path字段未在本切片统一清除。
