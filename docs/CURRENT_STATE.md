# Hify 当前实现边界

基线：2026-09-13 本地 `/Users/weberzhao/hify`，Git `main` 分支；Maven 3.9.16 已安装。初版已经完成单元/集成测试、Vue 生产构建、PostgreSQL/Flyway 容器启动和端到端 HTTP/SSE 冒烟验证。详细命令与结果见 `INITIAL_RELEASE.md`。

## 已实现并验证的初版能力

| 能力 | 代码证据 | 当前边界 |
|---|---|---|
| Spring Boot 入口 | `HifyApplication.java`、`HealthController.java` | Spring MVC/Tomcat 已运行验证；`GET /api/v1/health` 返回统一 Result 和 HTTP 200 |
| Provider/Model | `hify-provider/provider`、`ProviderController`、`V5__provider_catalog.sql` | OpenAI/Anthropic/Gemini 原生协议 + OpenAI-compatible；分页 CRUD、模型目录、credentialRef、缓存、独立健康检查和原生 SSE 已由契约测试验证 |
| Agent 管理/发布 | `hify-agent/agent`、`AgentController`、`ToolCatalogController`、`V6-V8` migrations | 草稿 CRUD、归档、动态 Tool Catalog、独立工具绑定、Provider/model/tool 校验、不可变发布版本与工具快照、精确未发布变更状态、批量列表和 Agent Console 已验证 |
| Conversation/Message | `hify-app/RunController`、`hify-chat` repositories | 创建会话时固定已发布 AgentVersion；Run 再保存 versionId/digest；无用户和会话分页 |
| Run/Event | `hify-chat` 的 `AgentRun`、`RunEvent`、`RunApplicationService` | 并发幂等、异步执行、终态 CAS、持久化取消、事件持久化和 SSE replay 已验证 |
| Query Loop | `runtime/QueryLoop.java`、`runtime/plan`、`runtime/state` | 六出口 ContinuationDecision、Claim/Evidence/Gap、可执行 FinishGate、有限 Retry、确定性 read-only Replan、no-progress 和明确终态均有测试 |
| Checkpoint/恢复 | `RunCheckpoint`、`V3__run_replan_control.sql`、`V4__run_context_state.sql` | 保存已配对消息、预算、完整 Plan 与 Evidence/Gap 版本化快照；启动恢复 RUNNING，结构化输入通过新子 Run 恢复 NEEDS_INPUT Gap |
| Runtime 能力/历史提交 | `CapabilitySnapshot`、`ToolExecutionLease`、`CommittedHistoryWriter`、`V9__runtime_capability_and_history.sql` | Run 固定 capability/tool schema 摘要；执行前二次校验 attempt lease；模型/工具结果按 operation identity 提交、回读、投影，冲突不覆盖 |
| Context 治理 | `runtime/context/*`、`ContextManagementEvaluationTest`、ADR-0010 | 输入/输出/预留/安全预算；Tool Result 先归档、checkpoint 后压缩且每步重测；评测语义安全而非只看 token 降幅 |
| 摘要与细节目录 | `memory/*`、`V11__context_memory_catalog.sql`、ADR-0012 | 所有 canonical message 建统一 DetailRef；结构化 checkpoint 摘要带逐 Claim sourceRefs、原文 digest 校验与冲突/缺源状态；摘要明确不作为证据 |
| 分层上下文/历史召回 | `LayeredContextMemoryService`、`HistoryRecallService`、`history.search/detail`、V12/V13、ADR-0013/0014 | 最近 3 轮原文 + 更早摘要/目录；PostgreSQL FTS + pgvector HNSW + 加权 RRF，并支持时间/类型/实体过滤；search 为导航、detail 才是 VERIFIED evidence；有 recall/no-progress 预算和只读 HTTP API |
| Knowledge/RAG 数据管线 | `hify-knowledge`、V14/V15、`KnowledgeApiIntegrationTest` | PostgreSQL 单库；TXT/Markdown 上传、持久索引任务、递归分块、确定性 bootstrap embedding、FTS/pgvector HNSW 候选与加权 RRF、canonical chunk digest/citation、归档已通过 H2 与 PostgreSQL 门禁；尚未绑定 AgentVersion/Chat ContextManager |
| Workflow 版本化运行时 | `hify-workflow`、V16、`WorkflowApiIntegrationTest` | 草稿图 CRUD、结构校验、不可变发布版本、START/TEMPLATE/CONDITION/KNOWLEDGE/END 确定性执行、变量池与节点轨迹已通过 H2/PostgreSQL 门禁；尚未绑定 Chat/Agent，也未接入 LLM/Tool 节点 |
| MCP Server 管理 | `hify-mcp`、V17、`McpServerApiIntegrationTest` | Streamable HTTP 初始化、tools/list 版本快照、schema digest、READ 工具 tools/call 调试、参数校验、credentialRef 与私网/重定向边界已验证；尚未绑定 AgentVersion/Query Loop，完整 SDK conformance 与 DNS rebinding 固定解析待补 |
| 子任务控制协议 | `ChildAgentTask`、`ChildAgentTaskService`、`V10__child_agent_task_protocol.sql` | 独立状态机、outputRef、delivered/claimed/consumed、父成功后确认、lost 收敛已验证；尚无真实子 Agent worker/调度器 |
| Intent Router | `hify-chat/com.hify.intent`、`IntentRoutingController` | 四出口契约、确定性规则层、结构化模型候选、低置信/歧义/缺槽澄清、120 条中文评测集；当前仅预览，不接管 Run |
| Mock 模型 | `MockModelClient.java` | 可触发时间或单个二元运算工具 |
| 模型协议适配 | `ProviderAdapterRegistry`、三个原生/一个兼容 Adapter | OpenAI tool_calls、Anthropic tool_use/tool_result、Gemini functionCall/functionResponse 的同步与流式映射；流式文本 delta 和工具参数重组已有测试 |
| 内置工具 | `ToolRuntime.java`、`ToolCatalogController.java` | current_time/calculator；稳定只读目录、schema 必填/类型校验、read 权限和 32 KiB output cap |
| 持久化 | `V1__baseline.sql`、JPA Entity | Flyway + PostgreSQL 16.15 部署验证；H2 只用于本地/测试便利 |
| Console | `frontend/` | Vue 3 + TypeScript + Vite + Element Plus；Provider/Agent/Knowledge/Workflow/MCP 均有真实数据管理页，Query Loop Playground 可用；管理面路由 smoke 与生产构建已通过 |
| 启停与部署 | `start.sh`、`stop.sh`、`Makefile`、`deploy/up.sh`、`compose.yaml` | 开发态入口 `http://localhost:5173`，容器入口 `http://localhost:8088`；PID、日志、健康轮询和失败回收已验证 |
| Chat Playground | 已发布 Agent 选择、Conversation 版本固定、真实 Provider/Mock 流式、Tool Loop、SSE replay/取消、Gap resume | 浏览器端合并 delta 并在终态回读 Run；PostgreSQL 验证旧会话保持旧版本、新会话固定新版本 |
| 后端工程 | `backend/pom.xml`、10 个子模块 | Maven reactor、统一 Result/异常、MyBatis-Plus/Redis 配置、业务模块空壳和 DemoItem 参考切片已构建验证 |
| 业务基础组件 | `hify-common`、`hify-demo`、`V2__demo_item.sql` | BaseEntity、分页、校验、ISO 时间、可选 Redis Cache、隔离线程池、LLM HTTP/SSE、provider 级熔断/分类重试和请求日志均有测试或运行证据 |

## 尚未实现

- 流式 429/5xx、认证失败、半途断流和浏览器断开场景的完整故障注入矩阵；当前三协议 happy path、共享 cancellation/deadline 与既有 Run 取消路径已接通。
- 精确 token/cost 计量；当前 token budget 是字符数估算，尚无价格表与成本预算。
- RunStep/ToolCall 独立表、通用完整 JSON Schema、工具超时与写工具交互式批准；当前 Attempt/Plan 通过事件追踪，checkpoint 与 canonical history 持久化 call/result 配对消息。
- 高风险 write/external/execute 工具策略；当前只有 read 工具，未绑定工具会被拒绝。
- 完整审计字段；并发幂等数据库冲突归一和终态数据库 CAS 已完成。
- MCP/Workflow/Knowledge 到 AgentVersion/Chat 的运行绑定、文档对象存储与 Redis；三者均已有独立纵向切片，原文暂存 PostgreSQL canonical_content，后续再抽象 S3-compatible object port。
- 认证/用户、完整 SSRF/DNS rebinding/redirect 防护、审计日志、CI 和 Vault/云 Secret Manager；Provider 当前仅允许 http/https、拒绝 userinfo/fragment，并支持 env/system 引用。
- MCP 当前使用受约束的 Streamable HTTP JSON-RPC 子集并关闭重定向；正式对接复杂 session/SSE/MRTR 服务前仍需接入官方 Java SDK 并跑 MCP conformance suite。
- JPA 到 MyBatis-Plus 的全仓 Repository 迁移；Provider 与 DemoItem 已迁移，Agent/Chat/Run 仍保留 JPA，禁止一次性重写。
- Intent Router 的真实 Provider 离线评测、shadow 事件和主链路 dispatch；当前 rule-only v2 Top1 为 82.50%（unknown recall 100%），模型层已有契约与单测但尚无真实成本/延迟数据。
- Workflow 已有显式条件分支和不可变发布版本，但尚无 LLM/Tool 节点、统一候选排序/选择记录、双层 TAO、真实子 Agent worker/调度器和阶段/全局回滚；当前只有子任务持久状态与延迟消费协议。
- write 工具的 planDigest 确认、side-effect ledger、幂等执行和 compensation；checkpoint 不能替代这些机制。

## 现状与目标架构的冲突

1. 旧同步 `/api/chat` 和长事务 `ChatService` 已删除；所有对话执行统一进入异步 Run API。
2. Provider 已完成 MyBatis-Plus 分层；Agent 已完成 Controller → application service 与 DTO，但持久层仍是 JPA，后续只做受测试保护的渐进迁移。
3. QueryLoop 已把 deadline/cancellation token 传入同步与原生 SSE Provider HTTP；底层 socket timeout 仍是上限，控制循环可提前取消连接。
4. `ToolDefinition.risk` 已执行 read-only policy，并具备 Try/Replan 状态机；还没有完整 write policy、精确确认 token、side-effect ledger、工具级超时和补偿动作。
5. Run 终态已使用 `state + version` 单 SQL compare-and-set；多副本事件序号仍需进一步设计。
6. PostgreSQL/Flyway、pgvector 与 HNSW 已验证；JSONB 深度利用和生产级备份恢复演练尚未进入本初版。
7. WebFlux 已移除并对齐 Spring MVC/SseEmitter；Provider 原生 token stream 已统一投影为持久 Run 事件。

## 处理原则

- 不删除原型后重写；先用 characterization tests 固定 mock provider、tool call/result 和会话行为。
- 当前初版优先建立纵向闭环；后续仍按 `PHASE_0_ALIGNMENT.md` 完成多模块和剩余契约，再扩展业务能力。
- README/设计文档分别使用“源码存在”“测试通过”“运行验证”三种证据等级，禁止统称“已完成”。
