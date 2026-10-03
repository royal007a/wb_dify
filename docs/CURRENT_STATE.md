# Hify 当前实现边界

初始审计基线：2026-10-03，原版 `/Users/weberzhao/hify`，`973257c`；本次源码与专项证据对齐至 `641fdcb`（2026-10-04）。下表区分实现与验收范围，不代表全功能、真实模型或部署全部复验。当前全接口/功能验收清单见 `spec/README.md`；发现与后续证据见 `spec/AUDIT_FINDINGS.md`。2026-09-13 初版证据留在 `INITIAL_RELEASE.md`，不能当作新功能的运行结果。

## 已实现能力与验证边界

| 能力 | 代码证据 | 当前边界 |
|---|---|---|
| Spring Boot 入口 | `HifyApplication.java`、`HealthController.java` | Spring MVC/Tomcat 已运行验证；`GET /api/v1/health` 返回统一 Result 和 HTTP 200 |
| Provider/Model | `hify-provider/provider`、`ProviderController`、`V5__provider_catalog.sql` | OpenAI/Anthropic/Gemini 原生协议 + OpenAI-compatible；分页 CRUD、模型目录、credentialRef、缓存、独立健康检查和原生 SSE 已由契约测试验证 |
| Agent 管理/发布 | `hify-agent/agent`、`AgentController`、`ToolCatalogController`、`V6-V8` migrations | 草稿 CRUD、归档、动态 Tool Catalog、独立工具绑定、Provider/model/tool 校验、不可变发布版本与工具快照、精确未发布变更状态、批量列表和 Agent Console 已验证 |
| Conversation/Message | `hify-app/RunController`、`hify-chat` repositories | 创建会话时固定已发布 AgentVersion；Run 再保存 versionId/digest；无用户和会话分页 |
| Run/Event | `hify-chat` 的 `AgentRun`、`RunEvent`、`RunApplicationService` | 并发幂等、终态/消息/事件同事务、提交后 SSE 游标追赶、游标归属与慢客户端隔离有专项 H2/PG/真实 socket 证据；订阅全局64线程，无用户配额。dispatch owner 只保单实例；结果不明后停用 Provider 导致无法重放的反例待修 |
| Query Loop | `runtime/QueryLoop.java`、`runtime/plan`、`runtime/state` | 六出口、FinishGate、有限 Retry、read-only Replan 有专项测试。门禁仅检查已声明的 required Claim/Gap；普通聊天无 required Claim 时可完成，不等于答案事实已验证。知识路径只核验来源，Workflow 不走此门禁 |
| Checkpoint/恢复 | `RunCheckpoint`、`CommittedHistoryWriter`、V3/V4/V22/V23 | 已提交 model/tool 操作重放、原文摘要与 JSON 语义比较；生产关闭顺序、重启前 interrupted/取消写入有 H2/PG 回归。未提交 READ 操作可能重做；旧工具历史 fail-closed；无外部副作用 exactly-once。Chat 恢复预算、replan 观察事件仍有缺口，真实 fork JVM 未测 |
| Runtime 能力/历史提交 | `CapabilitySnapshot`、`ToolExecutionLease`、`CommittedHistoryWriter`、`V9__runtime_capability_and_history.sql` | Run 固定 capability/tool schema 摘要；执行前二次校验 attempt lease；模型/工具结果按 operation identity 提交、回读、投影，冲突不覆盖 |
| Context 治理 | `runtime/context/*`、`ContextManagementEvaluationTest`、ADR-0010 | 输入/输出/预留/安全预算；Tool Result 先归档、checkpoint 后压缩且每步重测；评测语义安全而非只看 token 降幅 |
| 摘要与细节目录 | `memory/*`、`V11__context_memory_catalog.sql`、ADR-0012 | 普通聊天 canonical message 建 DetailRef；知识门禁 Run 的原始 memory 投影与旧索引读取隔离，最终答案仍由聊天交付。结构化 checkpoint 摘要带逐 Claim sourceRefs、原文 digest 校验与冲突/缺源状态；摘要明确不作为证据 |
| 分层上下文/历史召回 | `LayeredContextMemoryService`、`HistoryRecallService`、`history.search/detail`、V12/V13、ADR-0013/0014 | 普通 Run 最近3轮+早期摘要；FTS/pgvector/RRF、过滤和预算；search为导航、detail验证原文。知识门禁 Run 的 raw memory 与 layered 压缩关闭，含旧索引按来源隔离；PG 先截断后过滤可能降低普通 Run 召回 |
| Knowledge/RAG 数据管线 | `hify-knowledge`、V14/V15/V18、`KnowledgeApiIntegrationTest`、`KnowledgeFinishPostgresTest` | TXT/Markdown、递归分块、64维 hash bootstrap embedding、FTS/pgvector/HNSW + RRF、canonical引用；AgentVersion 冻结语料。空候选/来源失败在模型前拒绝；最终引用来源校验与缺口 gate、恢复 K 映射已有专项验证。绑定知识路径暂不推正文 delta。真实 embedding、无答案准确率与答案语义蕴含验证未实现，引用存在不等于事实正确 |
| Workflow 版本化运行时 | `hify-workflow`、V16/V19、`WorkflowApiIntegrationTest`、`WorkflowKnowledgePostgresTest` | AgentVersion/Chat、必经变量、最长路径50步、先解析后插值、DSL/corpus 快照、取消/期限均有专项回归；执行事实与父交付分开投影。旧未冻结/非法图需重新发布 Workflow→Agent→新会话；只保证图执行，非文本事实。全角空格、旧转义兼容、执行异常和崩溃恢复仍有缺口；无 LLM/Tool 节点 |
| MCP Server 管理 | `hify-mcp`、V17/V19/V20、`McpServerApiIntegrationTest`、McpCredential tests | Streamable HTTP JSON-RPC子集、工具发现/schema快照、READ调试；已绑定 AgentVersion/QueryLoop，冻结endpoint/credential/schema。Console可编辑、Token加密只写。官方SDK conformance、DNS固定解析未具备 |
| 子任务控制协议 | `ChildAgentTask`、`ChildAgentTaskService`、`V10__child_agent_task_protocol.sql` | 独立状态机、outputRef、delivered/claimed/consumed、父成功后确认、lost 收敛已验证；尚无真实子 Agent worker/调度器 |
| Intent Router | `hify-chat/com.hify.intent`、`IntentRoutingController` | 四出口契约、确定性规则层、结构化模型候选、低置信/歧义/缺槽澄清、120 条中文评测集；当前仅预览，不接管 Run |
| Mock 模型 | `MockModelClient.java` | 可触发时间或单个二元运算工具 |
| 模型协议适配 | `ProviderAdapterRegistry`、三个原生/一个兼容 Adapter | OpenAI tool_calls、Anthropic tool_use/tool_result、Gemini functionCall/functionResponse 的同步与流式映射；流式文本 delta 和工具参数重组已有测试 |
| 内置工具 | `ToolRuntime.java`、`ToolCatalogController.java` | current_time/calculator；稳定只读目录、schema 必填/类型校验、read 权限和 32 KiB output cap |
| 持久化 | `V1__baseline.sql`、JPA Entity | Flyway + PostgreSQL 16.15 部署验证；H2 只用于本地/测试便利 |
| Console | `frontend/` | Vue 3 + TypeScript + Vite + Element Plus；六个页面调用API，Workflow同源DSL画布/diff、能力绑定和MCP编辑已实现。management.spec.ts 是模拟路由页面测试，不证明真实CRUD；Chat/MCP另有 opt-in真实服务 smoke |
| 启停与部署 | `start.sh`、`stop.sh`、`Makefile`、`deploy/up.sh`、`compose.yaml` | 开发态入口 `http://localhost:5173`，容器入口 `http://localhost:8088`；PID、日志、健康轮询和失败回收已验证；本地脚本使用 `pgvector/pgvector:pg16` 以满足 V13 |
| Chat Playground | 已发布 Agent、固定版本、Tool Loop、SSE/取消、Gap resume | 21项受控浏览器测试覆盖代际隔离、同key快照重试、创建中取消、过期/空Gap、终态补读；不等于真实端到端。重试前后端校验顺序导致永久4xx锁页的P1待修；未知提交取消仍会重新POST，刷新会丢页面内身份 |
| 后端工程 | `backend/pom.xml`、10 个子模块 | Maven reactor、统一 Result/异常、MyBatis-Plus/Redis 配置、业务模块空壳和 DemoItem 参考切片已构建验证 |
| 业务基础组件 | `hify-common`、`hify-demo`、`V2__demo_item.sql` | BaseEntity、分页、校验、ISO 时间、可选 Redis Cache、隔离线程池、LLM HTTP/SSE、provider 级熔断/分类重试和请求日志均有测试或运行证据 |

## 尚未实现

- 真实供应商/浏览器全故障链路仍需扩大；共享 HTTP 的 401/429/5xx、半途断流、取消、重定向已有 LlmHttpClientTest，三协议原生映射已有 NativeProviderModelClientTest。不能再写成“故障矩阵全无”，也不能宣称每家真实服务全验收。
- 精确 token/cost 计量；当前 token budget 是字符数估算，尚无价格表与成本预算。
- RunStep/ToolCall 独立表、通用完整 JSON Schema、通用逐工具预算与写工具交互式批准；MCP/Provider已有HTTP超时/取消控制，不能泛称所有工具均无超时。Attempt/Plan 通过事件追踪，checkpoint/history 持久化 call/result。
- 高风险 write/external/execute 工具策略；当前只有 read 工具，未绑定工具会被拒绝。
- 完整审计字段；并发幂等数据库冲突归一和终态数据库 CAS 已完成。
- 三类能力已完成发布绑定；文档原文暂存 PostgreSQL canonical_content，尚无对象存储接入。Knowledge/Workflow强证据与取消等问题见审计A01-A04。
- 认证/用户、完整 DNS rebinding 防护、完整安全审计、CI 和 Vault/云 Secret Manager；Provider/MCP已有运行时URL/DNS检查与禁止重定向，但检查与连接解析间窗口未关闭。
- MCP 当前使用受约束的 Streamable HTTP JSON-RPC 子集并关闭重定向；正式对接复杂 session/SSE/MRTR 服务前仍需接入官方 Java SDK 并跑 MCP conformance suite。
- JPA 到 MyBatis-Plus 的全仓 Repository 迁移；Provider 与 DemoItem 已迁移，Agent/Chat/Run 仍保留 JPA，禁止一次性重写。
- Intent Router 的真实 Provider 离线评测、shadow 事件和主链路 dispatch；当前 rule-only v2 Top1 为 82.50%（unknown recall 100%），模型层已有契约与单测但尚无真实成本/延迟数据。
- Workflow 已有显式条件分支和不可变发布版本，但尚无 LLM/Tool 节点、统一候选排序/选择记录、双层 TAO、真实子 Agent worker/调度器和阶段/全局回滚；当前只有子任务持久状态与延迟消费协议。
- write 工具的 planDigest 确认、side-effect ledger、幂等执行和 compensation；checkpoint 不能替代这些机制。
- 原版没有 hify-cc 的父子文档、动态多路/迭代 RetrievalSession 评测成果；必须针对本仓库独立做数据与质量基线，不能借用另一个仓库的分数。

## 近期部署事实

132 原版入口 `https://118.196.123.132/hify/`，最近一次已记录部署为 `d06034e`，Token适配证据见 `evidence/MCP_TOKEN_INPUT.md`；上表后续审计修复尚未重新部署。`/api/v1/mcp` 是该主机上另一服务，不是 Hify 管理接口；课程 hify-cc 独立。Token主密钥文件不得随部署重建。无登录鉴权，入口访问控制与凭据轮换仍是运维责任。

## 现状与目标架构的冲突

1. 旧同步 `/api/chat` 和长事务 `ChatService` 已删除；所有对话执行统一进入异步 Run API。
2. Provider 已完成 MyBatis-Plus 分层；Agent 已完成 Controller → application service 与 DTO，但持久层仍是 JPA，后续只做受测试保护的渐进迁移。
3. QueryLoop 已把 deadline/cancellation token 传入同步与原生 SSE Provider HTTP；底层 socket timeout 仍是上限，控制循环可提前取消连接。
4. `ToolDefinition.risk` 已执行 read-only policy，并具备 Try/Replan 状态机；还没有完整 write policy、精确确认 token、side-effect ledger、工具级超时和补偿动作。
5. Run 终态使用行锁与 `state + version` CAS；事件序号已在 Run 行锁内分配。执行认领仅本地 owner，无分布式 lease；多副本/滚动重叠不支持，要求先停旧实例。
6. PostgreSQL/Flyway、pgvector 与 HNSW 已验证；JSONB 深度利用和生产级备份恢复演练尚未进入本初版。
7. WebFlux 已移除并对齐 Spring MVC/SseEmitter；Provider 原生 token stream 已统一投影为持久 Run 事件。

## 处理原则

- 不删除原型后重写；先用 characterization tests 固定 mock provider、tool call/result 和会话行为。
- 当前初版优先建立纵向闭环；后续仍按 `PHASE_0_ALIGNMENT.md` 完成多模块和剩余契约，再扩展业务能力。
- README/设计文档分别使用“源码存在”“测试通过”“运行验证”三种证据等级，禁止统称“已完成”。
