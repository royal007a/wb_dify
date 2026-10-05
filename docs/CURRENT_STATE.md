# Hify 当前实现边界

2026-10-05 15:02:01 CST本地补强：WORKFLOW-AGGREGATION-002代码5010e05通过harness/backend/frontend（97类685项，零失败/错误/skip/flaky，Python78项，typecheck/build通过）。旧writer固定金样、独立祖先负例及突变、逐字JSON草稿编辑补强已经交付；受控管理浏览器另行3/3。独立静态复核无P0/P1/P2，四类P3保留在`evidence/WORKFLOW_AGGREGATION_002.md`。没有部署132。

2026-10-05 14:41:25 CST本地增量：WORKFLOW-AGGREGATION-001实现379d393，通过harness/backend/frontend显式门禁（97类683项全部执行，失败/错误/skip/flaky均0，Python78项，typecheck/build通过）；另行管理浏览器2项为HTTP打桩。新增标量互斥分支聚合，静态要求每条结构路径恰有一个候选，不是并行join/迭代。没有部署132，独立代码复核进行中。详见`evidence/WORKFLOW_AGGREGATION_001.md`；下方“最新”均为各历史切片当时记录，线上仍以部署段为准。

最新132增量发布：2026-10-05 00:40窗口后安装a510191同源码树重新构建产物，00:50:21 CST收尾通过；见`evidence/CAPABILITY_ROLLOUT_001.md`。V24全部成功、service active/health200、在途三表0，jar5ee59af0/indexa724db59。真实豆包工作流输出42、具名输入/旧会话/上传边界及显式Demo浏览器2组5个Run通过；收尾harness78项和frontend通过。两轮验证脚本红灯保留，没有再次安装或迁移。语义代码已上线但没有线上embedding Provider，成功HTTP GET也未配置授权，不能称全部外部能力已上线可用。TLS自签、无登录、磁盘95%约1.98GiB、备份未恢复仍是边界。以下段落保留各时间点历史事实，不代表当前仍停留V23。

最新本地完整增量验收：2026-10-05 00:20:46 CST，a510191六scope通过，backend96类667项、migration15类125项、runtime34项、eval24项均零失败/错误/skip/flaky，harness78项；另行受控浏览器42/42、本机真实bge-m3三条合成改写及GET→qwen串联通过。Inputs002未编辑数字默认值补强、DEPLOY004产物V24目标与索引PENDING准入均通过。见`evidence/CAPABILITY_VERIFY_001.md`，原始日志不提交，不把路由映射和三条检索当全面质量验收。132仍是下述42db727/V23，尚未配置线上embedding Provider；本地新代码不等于已经发布。

2026-10-04 22点后增量：Dify官方逐篇差距映射见 `competitors/2026-10-04-dify.md`。本地已有SEMANTIC-001小型库语义检索与Workflow LLM/受控GET节点；特殊网段SSRF和前导零IPv4已在NODES-002/003修复、独立有界复核。WORKFLOW-INPUTS-001新增发布冻结的文本/数字/布尔/枚举输入及共用表单；0fc126f完整harness/backend/frontend于15:48:21Z通过，backend96类667项零失败/错误/skip/flaky，Harness74项，另行受控浏览器4/4；首轮门禁红灯保留。未编辑数字默认值的浏览器精度补强归INPUTS-002，HTTP内部防御剩余归HTTP-004。以上均未部署132；安装器必须先完成DEPLOY-004对V24的产物准入。下段42db727指最近一次线上完整发布基线，不代表新HEAD全部部署。

最新完整验收：2026-10-04 08:26:10Z，代码42db727、证据043c18f，六scope通过：backend87类602项、migration118项、runtime34项、eval24项均零失败/错误/skip/flaky（scope重叠不相加），Harness74项、另行受控浏览器38项通过。57具名子场景及脱敏离线重算已独立复核，不能把68路由/38功能组映射称为全部功能通过，见`evidence/SPEC_RELEASE_VERIFY_20261004.md`。默认Colima满盘未清理，改用独立验证profile真实PG完成。旧知识完整性和Chat005的满盘blocked记录保留，不回写历史。当前代码已完整重部署132，08:33:39Z线上检查和harness/frontend收尾通过，见`evidence/SPEC_DEPLOYMENT_FULL_20261004.md`。

初始审计基线：2026-10-03，原版 `/Users/weberzhao/hify`，`973257c`；源码对齐至 `9f40639`（2026-10-04）。下表区分源码存在、专项测试、历史运行，不代表全功能、真实模型或部署全部复验。历史审计001为495项中406通过/89条skip记录，Chat003为498项中409通过/89条skip记录；不回写或将历史skip冒充通过。新一轮SPEC-VERIFY后端539项/81类全部执行且零失败/错误/skip/flaky，包含真实隔离PG；逐方法子场景及其未测边界见 `evidence/SPEC_VERIFICATION.md`。该轮先出现背压测试夹具并发红灯，修复测试后重新全量运行，未掩盖首轮失败。当前验收清单见 `spec/README.md`；反例索引见 `spec/AUDIT_FINDINGS.md`；任务状态只看Harness。2026-09-13初版证据不能当作新功能的运行结果。

## 历史基线能力与验证边界

下表保留42db727一轮的基线描述；语义检索、LLM/受控GET节点和具名输入已经由上方增量证据覆盖，不能再用表内旧“未实现”推断当前源码仍不存在这些能力。线上状态以近期部署事实为准。

| 能力 | 代码证据 | 当前边界 |
|---|---|---|
| Spring Boot 入口 | `HifyApplication.java`、`HealthController.java` | Spring MVC/Tomcat 已运行验证；`GET /api/v1/health` 返回统一 Result 和 HTTP 200 |
| Provider/Model | `hify-provider/provider`、`ProviderController`、`V5__provider_catalog.sql` | OpenAI/Anthropic/Gemini 原生协议 + OpenAI-compatible；分页 CRUD、模型目录、credentialRef、缓存、独立健康检查和原生 SSE 已由契约测试验证 |
| Agent 管理/发布 | `hify-agent/agent`、`AgentController`、`ToolCatalogController`、`V6-V8` migrations | 源码有草稿CRUD/归档/目录/绑定校验/不可变发布快照和差异；AgentApiIntegrationTest提供后端专项证据。Agent Console的management测试是mock浏览器，不证明真实管理CRUD |
| Conversation/Message | `hify-app/RunController`、`hify-chat` repositories | 创建会话时固定已发布 AgentVersion；Run 再保存 versionId/digest；无用户和会话分页 |
| Run/Event | `hify-chat` 的 `AgentRun`、`RunEvent`、`RunApplicationService` | 终态/消息/事件同事务、提交后SSE、游标归属/慢客户端隔离有H2/PG/socket证据，SPEC-VERIFY本轮重跑PG零skip。订阅全局64线程，无用户配额；dispatch owner只保单实例。MockMvc/H2验证停用Provider后仍同key重放、异体拒绝、只读身份查询；不能将MockMvc说成真实网络 |
| Query Loop | `runtime/QueryLoop.java`、`runtime/plan`、`runtime/state` | 六出口、FinishGate、有限 Retry、read-only Replan 有专项测试。门禁仅检查已声明的 required Claim/Gap；普通聊天无 required Claim 时可完成，不等于答案事实已验证。知识路径只核验来源，Workflow 不走此门禁 |
| Checkpoint/恢复 | `RunCheckpoint`、`CommittedHistoryWriter`、V3/V4/V22/V23 | 已提交 model/tool 操作重放、原文摘要与 JSON 语义比较；生产关闭顺序、重启前 interrupted/取消写入有 H2/PG 回归。未提交 READ 操作可能重做；旧工具历史 fail-closed；无外部副作用 exactly-once。Chat 恢复预算、replan 观察事件仍有缺口，真实 fork JVM 未测 |
| Runtime 能力/历史提交 | `CapabilitySnapshot`、`ToolExecutionLease`、`CommittedHistoryWriter`、`V9__runtime_capability_and_history.sql` | Run 固定 capability/tool schema 摘要；执行前二次校验 attempt lease；模型/工具结果按 operation identity 提交、回读、投影，冲突不覆盖 |
| Context 治理 | `runtime/context/*`、`ContextManagementEvaluationTest`、ADR-0010 | 输入/输出/预留/安全预算；Tool Result 先归档、checkpoint 后压缩且每步重测；评测语义安全而非只看 token 降幅 |
| 摘要与细节目录 | `memory/*`、`V11__context_memory_catalog.sql`、ADR-0012 | 普通聊天 canonical message 建 DetailRef；知识门禁 Run 的原始 memory 投影与旧索引读取隔离，最终答案仍由聊天交付。结构化 checkpoint 摘要带逐 Claim sourceRefs、原文 digest 校验与冲突/缺源状态；摘要明确不作为证据 |
| 分层上下文/历史召回 | `LayeredContextMemoryService`、`HistoryRecallService`、`history.search/detail`、V12/V13、ADR-0013/0014 | 普通 Run 最近3轮+早期摘要；FTS/pgvector/RRF、过滤和预算；search为导航、detail验证原文。知识门禁 Run 的 raw memory 与 layered 压缩关闭，含旧索引按来源隔离；PG 先截断后过滤可能降低普通 Run 召回 |
| Knowledge/RAG 数据管线 | `hify-knowledge`、V14/V15/V18、`KnowledgeApiIntegrationTest`、`KnowledgeFinishPostgresTest` | TXT/Markdown、递归分块、64维 hash bootstrap embedding、FTS/pgvector/HNSW + RRF、canonical引用；AgentVersion 冻结语料。空候选/来源失败在模型前拒绝；最终引用来源校验与缺口 gate、恢复 K 映射已有专项验证。绑定知识路径暂不推正文 delta。真实 embedding、无答案准确率与答案语义蕴含验证未实现，引用存在不等于事实正确 |
| Workflow 版本化运行时 | `hify-workflow`、V16/V19、`WorkflowApiIntegrationTest`、`WorkflowKnowledgePostgresTest` | AgentVersion/Chat、必经变量、最长路径50步、先解析后插值、DSL/corpus 快照、取消/期限均有专项回归；执行事实与父交付分开投影。旧未冻结/非法图需重新发布 Workflow→Agent→新会话；只保证图执行，非文本事实。全角空格、旧转义兼容、执行异常和崩溃恢复仍有缺口；无 LLM/Tool 节点 |
| MCP Server 管理 | `hify-mcp`、V17/V19/V20、`McpServerApiIntegrationTest`、McpCredential tests | Streamable HTTP JSON-RPC子集、工具发现/schema快照、READ调试；已绑定 AgentVersion/QueryLoop，冻结endpoint/credential/schema。Console可编辑、Token加密只写。官方SDK conformance、DNS固定解析未具备 |
| 子任务控制协议 | `ChildAgentTask`、`ChildAgentTaskService`、`V10__child_agent_task_protocol.sql` | 源码有状态机、outputRef、delivered/claimed/consumed、父成功后确认；ChildAgentTaskTest/IntegrationTest覆盖受控lost转移，不证明生产恢复监听器顺序；无真实子Agent worker/调度器 |
| Intent Router | `hify-chat/com.hify.intent`、`IntentRoutingController` | 四出口契约、确定性规则层、结构化模型候选、低置信/歧义/缺槽澄清、120 条中文评测集；当前仅预览，不接管 Run |
| Mock 模型 | `MockModelClient.java` | 可触发时间或单个二元运算工具 |
| 模型协议适配 | `ProviderAdapterRegistry`、三个原生/一个兼容 Adapter | OpenAI tool_calls、Anthropic tool_use/tool_result、Gemini functionCall/functionResponse 的同步与流式映射；流式文本 delta 和工具参数重组已有测试 |
| 内置工具 | `ToolRuntime.java`、`ToolCatalogController.java` | current_time/calculator；稳定只读目录、schema 必填/类型校验、read 权限和 32 KiB output cap |
| 持久化 | `V1__baseline.sql`、JPA Entity | Flyway + PostgreSQL 16.15 部署验证；H2 只用于本地/测试便利 |
| Console | `frontend/` | Vue 3 + TypeScript + Vite + Element Plus；六个页面调用API，Workflow同源DSL画布/diff、能力绑定和MCP编辑已实现。management.spec.ts 是模拟路由页面测试，不证明真实CRUD；Chat/MCP另有 opt-in真实服务 smoke |
| 启停与部署 | `start.sh`、`stop.sh`、`Makefile`、`deploy/up.sh`、`compose.yaml` | 开发态入口 `http://localhost:5173`，容器入口 `http://localhost:8088`；PID、日志、健康轮询和失败回收已验证；本地脚本使用 `pgvector/pgvector:pg16` 以满足 V13 |
| Chat Playground | 已发布 Agent、固定版本、Tool Loop、SSE/取消、Gap resume | 83055fb的31项受控HTTP/SSE生命周期测试覆盖人工重连、放弃resume、取消文案和建会话10秒期限；未知取消仅GET，放弃脱离旧会话，刷新丢页面身份。SPEC-VERIFY全浏览器39项中仅4项为真实本地后端链路，其余35项打桩。Run输入001/002已修复超长输入、指定唯一约束识别、NUL及明确拒绝resume的入口，并随09:56增量发布；其他交互残余见SPEC-CHAT-LIFECYCLE-005，不宣称全部失败状态已验收 |
| 后端工程 | `backend/pom.xml`、10 个子模块 | Maven reactor、统一 Result/异常、MyBatis-Plus/Redis 配置、业务模块空壳和 DemoItem 参考切片已构建验证 |
| 业务基础组件 | `hify-common`、`hify-demo`、`V2__demo_item.sql` | BaseEntity、分页、校验、ISO 时间、可选 Redis Cache、隔离线程池、LLM HTTP/SSE、provider 级熔断/分类重试和请求日志均有测试或运行证据 |

## 尚未实现

- 真实供应商/浏览器全故障链路仍需扩大；共享 HTTP 的 401/429/5xx、半途断流、取消、重定向已有 LlmHttpClientTest，三协议原生映射已有 NativeProviderModelClientTest。不能再写成“故障矩阵全无”，也不能宣称每家真实服务全验收。
- 精确 token/cost 计量；当前 token budget 是字符数估算，尚无价格表与成本预算。
- RunStep/ToolCall 独立表、通用完整 JSON Schema、通用逐工具预算与写工具交互式批准；MCP/Provider已有HTTP超时/取消控制，不能泛称所有工具均无超时。Attempt/Plan 通过事件追踪，checkpoint/history 持久化 call/result。
- 高风险 write/external/execute 工具策略；当前只有 read 工具，未绑定工具会被拒绝。
- 完整审计字段仍缺。源码有并发幂等归一与终态CAS；RunAdmission/Dispatch/PG并发专项提供有限测试证据。SPEC-VERIFY重跑PG，不因此声称全部竞态已验收。
- 源码有Knowledge/Workflow/MCP发布绑定，专项Agent/MCP/Workflow知识测试验证各自路径，不代表三类能力任意组合端到端通过。原文存canonical_content，未接对象存储；证据/取消限制见审计A01-A04。
- 认证/用户、完整 DNS rebinding 防护、完整安全审计、CI 和 Vault/云 Secret Manager；Provider/MCP已有运行时URL/DNS检查与禁止重定向，但检查与连接解析间窗口未关闭。
- MCP 当前使用受约束的 Streamable HTTP JSON-RPC 子集并关闭重定向；正式对接复杂 session/SSE/MRTR 服务前仍需接入官方 Java SDK 并跑 MCP conformance suite。
- JPA 到 MyBatis-Plus 的全仓 Repository 迁移；Provider 与 DemoItem 已迁移，Agent/Chat/Run 仍保留 JPA，禁止一次性重写。
- Intent Router 的真实 Provider 离线评测、shadow 事件和主链路 dispatch；当前 rule-only v2 Top1 为 82.50%（unknown recall 100%），模型层已有契约与单测但尚无真实成本/延迟数据。
- Workflow 已有显式条件分支、不可变发布版本、LLM/精确授权GET和具名输入；尚无通用Tool节点、统一候选排序/选择记录、双层TAO、真实子Agent worker/调度器和阶段/全局回滚；子任务仍只有持久状态与延迟消费协议。
- write 工具的 planDigest 确认、side-effect ledger、幂等执行和 compensation；checkpoint 不能替代这些机制。
- 原版没有 hify-cc 的父子文档、动态多路/迭代 RetrievalSession 评测成果；必须针对本仓库独立做数据与质量基线，不能借用另一个仓库的分数。

## 近期部署事实

最新完整发布：2026-10-04 16:33:39 CST完成线上检查及收尾门禁，42db727构建（发布基线043c18f），输入卫生001–003、知识完整性003和Chat005当前代码已上线。真实132三项浏览器、NUL管理/绑定/检索/Run拒绝、合法Run和幂等、直连与/hify上传边界通过；服务active、health200、V23全部成功、三类在途数0。jar3aa5eb39/index81242978，主密钥文件元数据未变，未读内容。磁盘97%、约1.28GiB余量，未擅自清理；备份只做list检查、未恢复。详见`evidence/SPEC_DEPLOYMENT_FULL_20261004.md`。仍无认证/真实供应商效果保证，TLS自签。以下保留历史发布证据。

历史增量：2026-10-04 09:56 CST，5f49629构建（应用源树等于f0dd199的547项门禁），见`evidence/SPEC_DEPLOYMENT_INCREMENT.md`；当时磁盘约1.55GiB，此数字不代表当前余量。

132 原版入口 `https://118.196.123.132/hify/`，2026-10-04已从d06034e升级到a66be9e构建（应用源码树与9f40639验收一致），V21–V23成功；详情见 `evidence/SPEC_DEPLOYMENT.md`。直连和/hify上传边界、合成知识检索、3项真实132浏览器通过，不等于全功能/真实模型验收。证书仍自签名，磁盘约1.8GiB余量，teacher_mcp旧env引用未自动授权。`/api/v1/mcp` 是另一服务，不是 Hify 管理接口；hify-cc独立且未改。Token主密钥未重建。无登录鉴权，入口访问控制与凭据轮换仍是运维责任。

## 现状与目标架构的冲突

1. 旧同步 `/api/chat` 和长事务 `ChatService` 已删除；所有对话执行统一进入异步 Run API。
2. Provider 已完成 MyBatis-Plus 分层；Agent 已完成 Controller → application service 与 DTO，但持久层仍是 JPA，后续只做受测试保护的渐进迁移。
3. QueryLoop 已把 deadline/cancellation token 传入同步与原生 SSE Provider HTTP；底层 socket timeout 仍是上限，控制循环可提前取消连接。
4. `ToolDefinition.risk` 已执行 read-only policy，并具备 Try/Replan 状态机；还没有完整 write policy、精确确认 token、side-effect ledger、工具级超时和补偿动作。
5. Run 终态使用行锁与 `state + version` CAS；事件序号已在 Run 行锁内分配。执行认领仅本地 owner，无分布式 lease；多副本/滚动重叠不支持，要求先停旧实例。
6. PostgreSQL/Flyway、pgvector/HNSW有历史部署及专项PG证据；历史审计001/Chat003的11个PG类跳过仍保留，新鲜PG结果独立记录于SPEC-VERIFY，不回溯更改历史。JSONB深度利用和生产级备份恢复演练尚未验证。
7. WebFlux 已移除并对齐 Spring MVC/SseEmitter；Provider 原生 token stream 已统一投影为持久 Run 事件。

## 处理原则

以下反例尚未由上述专项关闭，具体状态在tasks.json，不以本页另建状态板：

- 工程证据门禁：SPEC-AUDIT-004的cee737f已补finish实际日志/摘要SHA和运行身份、精确历史兼容前缀，04:27:20Z五步harness与64项Python通过，待独立复核。首轮部署测试5秒门闩失败保留；未改应用或部署，也不等于重新运行后端。便携validate允许缺ignored日志但不宣称复算，不能用于finish放行。

- 发布故障路径：SPEC-DEPLOY-003的stderr写失败P1和005的清理SIGPIPE/最终quiet检查P2已经独立复验，609829e脚本随006成功发布；旧426de39仍不是可用安装器。非标准smoke响应/索引静默/schema目标归SPEC-DEPLOY-004。主流程stderr断管道仍可能fail-closed中止，本次用systemd/journal执行；不宣称所有失败路径安全。
- Run输入001/002：20000 UTF-16上限、指定幂等唯一约束、并发删除会话404及明确拒绝resume的出口已有543项后端/32项受控浏览器历史证据；f0dd199追加结构化PG约束字段和NUL拦截后547项后端零skip，09:56已部署132并实测。重复gapId撑爆的反例已撤回。其他Chat恢复P3与全数据库故障排列仍非保证。
- 输入卫生001–003：上传、基本管理文本、会话标题、Workflow试跑与四类Agent绑定NUL前置400，选定检索/memory/意图读准入已有历史585项和本轮602项后端零skip证据，已随本次完整发布上线132并抽测。002和003均经独立archive重算SHA、源码身份及离线报告闭环，复核方未重新运行Maven。保留多元素后项非法专门用例/仅路径ID边界；内部模型文本也按PARAM_ERROR拒绝且不清洗，不代表一定是用户错误。不能概称全入口完成。132磁盘97%、余量约1.28GiB，下次发布前须明确清理范围或扩容，未擅自删除备份。

- 总验收补录：管理分页400/夹值尚未统一（SPEC-API-PAGINATION-001）；在途GET人工重连、取消查询按钮、跨会话提示已由f20bd34实现，34项受控浏览器及17项H2通过。SPEC-CHAT-LIFECYCLE-005旧满盘blocked运行保留；当前代码已由SPEC-RELEASE-VERIFY-001完整backend及受控浏览器覆盖并随DEPLOY-007上线，不伪造旧记录。见SPEC_CHAT_INFLIGHT_RECOVERY和本轮完整证据。明确拒绝resume循环已随RUN-INPUT-001修复。send阻塞后再次提交断言、报告partial传播与脱敏可复算输入已由SPEC-VERIFY-002补齐，原始XML仍不提交；输入不是签名。

- 知识：部分来源失败仍先发completed；wrapped suspension/数据库故障可能转Gap，见SPEC-KNOWLEDGE-FINISH-004。索引isPostgres另借连接/失败当H2和Agent固定Workflow checksum未比对已由1388db6修复，77项窄测通过，独立静态复核认可；df7a92b补强合法Agent执行的精确写入计数。SPEC-KNOWLEDGE-INTEGRITY-003旧满盘blocked记录保留，当前代码由SPEC-RELEASE-VERIFY-001的602项backend及118项migration零skip完整验证覆盖，已随DEPLOY-007部署，不把线上抽测说成全故障验证。
- 恢复：Chat每次重启重置完整runTimeout（SPEC-RUN-BUDGET-001）；recallLatency/replanDecisions重置、轮末replan生成新UUID及重复观察事件（SPEC-HISTORY-RECOVERY-003）。
- 调度/关闭：拒绝后终态写库失败可留RUNNING、恢复超过104容量可判FAILED、WorkflowRecovery整体UPDATE失败阻止启动，统一归SPEC-RECOVERY-ADMISSION-001，已有三项对应验收。子任务监听器顺序另归SPEC-CHILD-RECOVERY-001。
- Workflow：执行前异常/成功后读取失败仍可落MODEL_ERROR；END节点SUCCEEDED可与父CANCELLED不同；成功事实落盘后崩溃可重新执行（SPEC-WORKFLOW-RECOVERY-001）。started早于校验而无failed投影归SPEC-WORKFLOW-GRAPH-003。
- 表达式：全角空格、旧反斜杠解码、旧裸help!/A&B/半角括号版本兼容尚有问题（SPEC-WORKFLOW-GRAPH-003）。
- 资源边界：SSE每连接独占线程，慢读可延长单次send，180秒不是硬总期限；Run先截止时breaker只释放、不计供应商超时（SPEC-SSE-BACKPRESSURE-002、SPEC-PROVIDER-SAMPLING-001）。
- 凭据：禁止用-D/JAVA_OPTS传密钥；sun.java.command等进程启动配置不在引用名单保护范围，不能误授权；见OPERATIONS。Chat004与Run输入001/002已复核关闭，其他管理文本输入卫生独立记录，不外推这些修复。

- 门禁补强SPEC-AUDIT-005：0fee0df绑定规范run目录和每步日志、按类复核totals及当次期望集合、固定legacy清单身份；实际harness74项/五步通过，待独立复核。任意仓库写者回滚任务状态仍是非目标，不宣称签名；未运行Maven/PG或部署，不能解除Chat/知识完整门禁阻塞。

- 不删除原型后重写；先用 characterization tests 固定 mock provider、tool call/result 和会话行为。
- 当前初版优先建立纵向闭环；后续仍按 `PHASE_0_ALIGNMENT.md` 完成多模块和剩余契约，再扩展业务能力。
- README/设计文档分别使用“源码存在”“测试通过”“运行验证”三种证据等级，禁止统称“已完成”。
