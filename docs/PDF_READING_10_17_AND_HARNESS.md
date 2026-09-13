# 课程 10–17 与 Harness 专栏阅读结论

阅读基线：2026-09-13。范围包括 Hify 课程 10–17 八份 PDF，以及 Harness 专栏目录、开篇、架构演进、Main Loop、Thinking、Provider、Tool Registry 七份 PDF。本文只保留会改变 Hify 设计或实施顺序的结论。

## 1. 总结

两组材料指向同一工程方法：模型负责提出候选和完成高生成量工作，代码与制度负责边界、状态、恢复和验收。Hify 不应追求一次性铺满 Dify，而应连续交付可运行纵向切片；每个切片都经历领域理解、方案取舍、契约、实现、验证和知识回写。

本轮因此选择两个最短但高价值的缺口：

1. Agent 从“可变配置行”升级为“草稿 + 不可变发布版本”，Conversation/Run 固定版本与 digest。
2. 三种原生 Provider 与 OpenAI-compatible 从“完成后整段事件”升级为原生 SSE delta，经 Query Loop 投影为 `message.delta`。

## 2. Hify 课程 10–14：基础设施如何兑现

### 10｜后端业务基础组件

- 先咨询查漏，再按“必须运行、业务复用、健壮性”分层实施。
- schema、Mapper 扫描和隔离线程池是容易遗漏但会直接阻塞业务的基础。
- BaseEntity、分页、校验、统一时间、Cache、HTTP/SSE 客户端、熔断和结构化日志应由真实 CRUD 切片共同验收。

Hify 现状：这些能力均已有真实消费者和测试；Flyway/PostgreSQL 比课程的 `schema.sql` 更适合可演进交付。保留 H2 仅作兼容测试，不把它视为生产事实源。

### 11｜前端设计系统与公共组件

- 管理后台采用浅内容区、深侧栏、蓝紫主色和青绿状态色；CSS token 比页面散落色值更重要。
- 表格、表单弹窗、确认、请求三态、通知等重复交互应先形成公共组件。
- UI 质量来自“初稿—观察—具体微调”，不是一次提示生成完美页面。

Hify 现状：设计 token、响应式布局、HifyTable/HifyFormDialog/useConfirm/useRequest/notify 已落地；Provider 与本轮 Agent 页面复用同一套组件，证明基础设施已产生复利。

### 12｜工程搭建实操

- 任务粒度是“一个可以独立验收的单元”，不是固定文件数。
- 报错要带完整上下文，修复根因后再继续依赖步骤。
- SDD 是日常闭环：规范 → AI 执行 → 偏差 → 修代码和最小必要规范。

本轮应用：Agent 后端、Agent Console、native streaming、文档/Skill 分开提交；每步先跑窄测试，再进入全矩阵。

### 13｜Provider 完整交付

课程的关键设计是：小型自有适配层、协议类型可扩展、鉴权 JSON、展示名/调用 ID 分离、健康状态独立表、后端到前端完整交付。

Hify 的有意识差异：

| 课程方案 | Hify 方案 | 原因 |
|---|---|---|
| OpenAI/Claude/Ollama/compatible | OpenAI/Anthropic/Gemini/compatible | 当前目标协议；本地模型可由 compatible 接入 |
| auth_config 可保存 API Key | 只保存 credentialRef 与协议元数据 | 降低数据库和 API 泄密半径 |
| 初期 if/else 分发 | 小型 Adapter Registry | 已有四种协议且需要同步/流式双入口 |
| MySQL DDL | PostgreSQL + Flyway | 与既有 ADR 和可恢复迁移一致 |

### 14｜把经验变成 Skill

- Skill 是按需加载的模块交付流程；AGENTS.md 是始终有效的项目地图与硬规则，两者不能混用。
- 有价值的 Skill 要包含输入、输出、决策点、验收和常见偏差，不能只是泛化口号。
- Skill 必须从真实模块复盘中迭代。

本轮产物：`.codex/skills/hify-module-delivery/SKILL.md`，固化四问理解、决策记录、分层交付、验证矩阵、SDD 回写和 split commit。

## 3. Hify 课程 15–17：Agent 与对话引擎

### 15｜Agent 创建与配置

Agent 的核心是身份/系统指令、模型绑定、工具绑定和运行参数。课程选择一张可变 Agent 表并延后 ReAct，适合作为教学最小 CRUD，但生产运行存在配置漂移：历史会话会被后续编辑悄悄改变。

Hify 决策：管理端编辑草稿；发布产生不可变 AgentVersion；Conversation 在创建时绑定版本；Run 再保存 versionId 和 snapshotDigest。更新草稿不影响旧会话。固定常用参数继续用列，不为了“灵活”全部塞 JSON。

### 16｜对话链路与流式选型

- 对话引擎管理 Session、Message、上下文、LLM 调用、流式输出和落库。
- 面向浏览器的单向 token 推送使用 SSE；不为不需要的双向能力引入 WebSocket。
- SseEmitter 生命周期不能持有数据库事务；模型调用在隔离 executor 中运行。
- 客户端断开、运行取消和整体 deadline 必须传播到供应商连接。

Hify 决策：保留现有异步 Run + 持久事件 + SSE replay；原生 Provider 流只负责解析供应商 delta，统一由 RunEventBroker 发出 `message.delta`。这比直接把供应商连接暴露给浏览器更可恢复。

### 17｜多轮上下文与真正 token 流

- LLM 本身无状态，多轮能力来自按 Conversation 重建消息。
- 用户消息在模型调用前持久化，助手消息只在成功终态写入；错误不能吞掉用户输入。
- Redis 滑动窗口不是默认答案。数据库是事实源，只有上下文规模/性能数据证明必要时才引入缓存和一致性成本。

Hify 当前保留 PostgreSQL 消息历史，不增加 Redis 双写。本轮实现 OpenAI/compatible、Anthropic、Gemini 原生 SSE；流式请求不在已经发出 delta 后做整请求重试，避免文本与副作用重复。

## 4. Harness 专栏：对 Hify Runtime 的校准

### Framework → Harness

传统 DAG 把分支写死在程序中；Harness 用 Main Loop 让模型动态决定下一动作，同时由工具注册、安全中间件、状态和压缩机制提供物理边界。控制反转不代表把权限、成功判定或预算交给模型。

Hify 对应：QueryLoop 负责单 Run 循环，ToolRuntime 负责 schema/权限/结果上限，ExecutionContextState 负责 Evidence/Gap，FinishGate 由代码判定完成，Plan/Replan/Checkpoint 提供透明状态和恢复点。

### Main Loop

原生 tool call 与 tool result 必须按 ID 配对并回灌上下文。课程早期示例刻意极简，但工业实现仍需最大轮次、时间、token/cost、工具次数、取消和终态。

Hify 已超过示例基线：六出口 ContinuationDecision、有限 Retry、read-only Replan、no-progress、Evidence gate 和 checkpoint 均有测试；不会为了贴近示例删除预算。

### 独立 Thinking 阶段

在每次行动前额外进行一次禁止工具的“慢思考”可能减少冲动调用，但会增加一倍模型开销，并可能形成自我确认偏差。Hify 不默认增加固定双调用；只有高风险或评测证明收益时，才在 Plan/approval 层按条件启用。

### Provider 与 Tool Registry

Provider 层的职责是协议翻译，不能把供应商格式泄漏到 Query Loop。工具必须具有唯一名称、描述、JSON Schema、风险与执行器；参数先校验，结果要限长，文件/网络工具还需工作区与 SSRF 防护。

Hify 已有 Provider Adapter Registry 和 ToolRuntime；当前工具仅 `current_time/calculator`。MCP、渐进式工具披露、上下文压缩和通用中间件按真实规模触发，不在本轮铺空壳。

## 5. 本轮落地与剩余差距

已落地：Agent CRUD 草稿、发布版本、版本列表、模型有效性校验、agent-cache 失效、Conversation/Run 版本绑定、Console Agent 页面；三类原生协议和 compatible 的 native stream、delta 事件、共享 cancel/deadline；协议契约测试和版本钉住集成测试。

仍需后续数据触发：精确 usage/cost、断流后的客户端续接语义、Provider 429/5xx 流式故障注入、上下文 token 裁剪、工具级 timeout、MCP/Skill 渐进披露、写工具确认与副作用账本、真实用户与租户隔离。

## 6. 后续执行模板

领域四问 → 当前/目标差距 → 方案与淘汰条件 → 数据/API/事件契约 → 后端分层 → 窄测试 → 前端 API/页面 → 生产构建 → PostgreSQL/JAR/部署冒烟 → 当前状态与计划回写 → 按关注点拆分提交。
