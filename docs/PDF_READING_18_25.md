# 课程 18–25：详细阅读、事实核验与 Hify 取舍

本文覆盖八份课程 PDF：复杂流式聊天、核心功能全流程、RAG 上/下、Workflow 上/下、MCP 上/下。它记录课程真正讲了什么、哪些方法应吸收，以及哪些示例不能直接照搬到 Hify。

## 1. 总结结论

八讲形成同一条工程主线：先把需求按依赖拆小，用可独立验收的基础设施承接模板代码；新增能力先单独跑通，再寻找对既有主链路的最小侵入点；最后同时验证新能力、旧能力和异常边界。RAG、Workflow、MCP 不是三个孤立页面，而是逐步把 Agent 从“能聊天”推进到“引用证据、按步骤处理、调用真实系统”。

Hify 采用这条方法，但不复制课程实现。课程示例面向教学，使用 MySQL + PostgreSQL 双库、Agent 上可变外键、同步工作流和较薄的安全边界；Hify 已有不可变 AgentVersion、异步 Run、SSE replay/cancel、Evidence/FinishGate、PostgreSQL 单事实源和 capability lease。新能力必须接入这些现有契约，而不是绕开它们另建一条 ChatService 快路。

## 2. 第 18 讲：复杂流式聊天界面

课程把页面需求分成四类：布局、数据、交互、API；动态交互则改用时间线描述。流式聊天的关键不是 CSS，而是发送后立即清空输入、乐观追加用户气泡、创建空 assistant 气泡、逐 delta 合并、终态收敛、异常恢复和智能自动滚动。浏览器验收必须覆盖 Browser → proxy → backend → SSE → renderer，curl 通过不能替代完整链路。

课程用 `fetch(POST)` 消费 SSE，因为原生 EventSource 只支持 GET。Hify 明确不采用这一传输：POST 只创建持久 Run，GET `/events` 使用 EventSource 并按 event id replay。这样幂等、断线重连、取消和历史回读都有独立资源语义。前端仍吸收时间线描述、增量渲染、Markdown 安全渲染、终态回读和滚动策略。

## 3. 第 19 讲：核心功能全流程

最重要的不是 CRUD，而是“先做决策，再按层交付”。Provider 示例体现四个设计判断：鉴权元数据用 JSON 保持适配弹性；展示名和调用 modelId 分离；健康状态独立成表避免高频写污染配置与缓存；新增类型通过小型适配层扩展而不是引入重框架。交付顺序是 Entity/Mapper → DTO → Service → Controller → curl → frontend API → browser。

Hify 已经落实这些 Provider 决策，并进一步用 credentialRef 禁止原始密钥入库、用发布版本固定运行配置。课程强调的 Skill/规范闭环，在 Hify 中对应 AGENTS、docs、Harness tasks/evidence 和分拆提交。

## 4. 第 20–21 讲：RAG 探路与集成

RAG 数据管线由解析、递归分块、embedding、向量存储、检索、Prompt/Context 组装构成。课程一期支持 TXT、Markdown 和文字型 PDF，建议 512 token/64 overlap；文档处理异步，以 PENDING → PROCESSING → DONE/FAILED 驱动前端；检索默认 topK=3、阈值 0.75。其方法论是：从业务痛点建立心智模型，拆组件缩小陌生范围，以约束做选型，最小 Demo 先建立手感。

课程选择 pgvector 是因为已有 PostgreSQL 且数据量小，但示例仍把业务表放 MySQL、向量放 PostgreSQL。Hify 拒绝双库：knowledge/document/chunk/index task 全部在 PostgreSQL，事务和恢复更简单。Hify 一期只收 TXT/Markdown，扫描 PDF/OCR 与复杂表格延后；chunk 是引用事实源，向量和 FTS 都只是可重建索引。

接入主链路时，课程强调“先单独验收管线，再最小侵入 Chat”。Hify 把检索放入 ContextManager，而不是改一个 `buildMessages` 大方法；结果必须携带 citation/source revision，最终 Claim 需要回到 canonical chunk 才能成为 VERIFIED evidence。无知识库、无命中、索引失败不能改变既有 Run 行为或偷偷用模型常识冒充资料答案。

## 5. 第 22–23 讲：Workflow 元数据与执行引擎

课程用“节点存做什么，连线存做完去哪”解释工作流：持久化是 workflow/node/edge，执行时变成 nodeMap/edgeMap + while loop。不同节点 config 用 JSON，Java 用 sealed interface/record 做类型安全解析。执行上下文借鉴 Dify VariablePool，节点输出写入 `nodeKey.varName`，历史输出只增不改；run/node-run 支持回放、耗时与错误定位。

课程建议小团队使用同步轻量引擎，不引 Redis Queue、Temporal 或代码沙箱；按线性执行 → 条件分支 → 错误处理 → Chat 接入逐步实现。异常 review 要优先覆盖：默认分支缺失、目标节点不存在、环、步数超限、节点失败后的父子状态一致。

Hify 采用受限 DSL，但增加不可变 WorkflowVersion：草稿可替换保存，发布版本不可修改，Run 固定 versionId/digest。MVP 支持 START/TEMPLATE/CONDITION/KNOWLEDGE/END；不支持任意循环、并行、可视化画布、代码节点或分布式恢复。工作流必须复用统一预算、取消、Evidence 与事件契约，不能因“同步引擎”绕过 Run 控制面。

## 6. 第 24–25 讲：MCP Client 与自建 Server

MCP 的价值是把 M 个平台 × N 个工具的专用适配，收敛为平台 Client 与标准 Server。LLM 只产生结构化 function/tool call，Hify 才执行工具；工具 schema 的 description 决定模型何时选择工具。完整链路是：模型选择工具 → Client 调用 MCP Server → tool result 按 call id 回填 → 模型生成用户回答；必须有最大轮次和失败边界。

课程建议已有自研模型抽象时采用官方 Java SDK，而不是再引入 Spring AI 全家桶。Server 管理包含 CRUD、tools/list、工具快照、连通测试和调试面板；真实 Server 应从用户场景推导工具，而不是先拍脑袋做 API。工具返回既要有程序枚举，也可有面向模型/用户的 label。调试顺序是工具自身 → Hify 调试面板 → Agent 绑定 → 端到端对话。

课程退款例子是 write 工具，但其安全讨论只停留在思考题。Hify 当前只开放 read 调试：write/external/execute 在 planDigest 确认、side-effect ledger、幂等键和 compensation 完成前一律拒绝。MCP Server URL 还必须通过 scheme、DNS、redirect 和私网/metadata 防护；工具发现生成 schema digest，执行中的 Run 继续使用固定快照。

## 7. 采用、调整与拒绝

| 课程方案 | Hify 决策 | 原因 |
|---|---|---|
| POST 请求直接保持 SSE | 调整为 POST Run + GET EventSource | 支持持久 Run、replay、取消和重连 |
| MySQL 业务表 + PostgreSQL 向量 | 拒绝，统一 PostgreSQL + pgvector | 避免双库一致性和运维成本 |
| Agent 直接保存 knowledgeBaseId/workflowId | 调整为草稿绑定并在发布版本快照 | 旧会话和运行必须可复现 |
| 摘要/检索片段直接注入答案 | 调整为 citation + canonical chunk evidence | 摘要是导航，不能替代证据 |
| while-loop 同步 Workflow | 采用轻量内核，外层纳入 Run 预算/取消/事件 | 保持简单但不绕过控制面 |
| 官方 MCP SDK 协议层 | 原则采用，隔离在 transport adapter | 避免与既有 Provider/Tool 抽象冲突 |
| 退款 write Server 作为首个案例 | 拒绝，首个纵向切片只读 | 先完成确认、幂等、账本和补偿 |
| 一次生成完整模块 | 拒绝，按 Harness 原子任务与分拆提交 | 缩小 review 面和故障定位范围 |

## 8. 纵向交付顺序与验收

1. Knowledge：单库迁移 → TXT/Markdown 解析 → 分块 → FTS/pgvector RRF → citation → 管理 API/页面。
2. Workflow：草稿/发布 → 图校验 → 不可变变量池 → 线性/条件执行 → run/node-run → 试跑页面。
3. MCP：Server 目录 → tools/list 快照 → 只读 debug call → schema/风险/网络边界 → 调试页面。
4. 回归：Chat Run/SSE/replay/cancel、Provider、Agent 发布和历史会话不受影响。
5. 发布：全矩阵验证、分拆 commit、push、迁移、本地与远端浏览器 smoke。

质量不以“页面能打开”判断。Knowledge 同时看引用正确性、空命中、隔离与删除；Workflow 看结构还原、非法图、分支和执行记录；MCP 看协议错误、工具漂移、超时、取消、风险拒绝；前端看加载、错误、空状态和全链路浏览器行为。
