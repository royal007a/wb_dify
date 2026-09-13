# Hify 实施计划

原则：每阶段交付可运行纵向闭环，不能先铺满所有 Entity/Controller 再补运行时。下一阶段只有在上一阶段测试和证据通过后开始。`SPEC.md` 的 P0/P1 表示产品优先级；本文件的 Phase 表示有依赖关系的实施顺序，因此 P1 Knowledge/Workflow 分别位于 Phase 4/5。

## Phase 0：原型与契约对齐

2026-09-12 进度：Maven 多模块、公共 Result/异常/MyBatis-Plus/Redis/线程池/HTTP/熔断地基、DemoItem 参考切片、MVC/SSE、Flyway/PostgreSQL、Vue 3 + TypeScript、Nginx/容器部署均已完成；generated client/状态层、CI、pgvector 和更完整的架构测试仍未完成。权威差距见 `PHASE_0_ALIGNMENT.md` 和 `FOUNDATION_COMPONENTS.md`。

- 为现有 Provider/Agent/Chat/QueryLoop/Tool 原型补 characterization tests，先固定真实行为和已知缺陷。
- 建立 Maven parent + `hify-app/provider/agent/chat/tool/common` 模块。
- 按 ADR 对齐 Spring Boot、MVC/SSE、PostgreSQL/Flyway；移除 H2 作为验收数据库。
- 按 `PHASE_0_ALIGNMENT.md` 处理现有单模块 WebFlux/JPA/H2 原型，不把依赖存在当作技术决策完成。
- 建 Vue 3 + TypeScript 基础壳、OpenAPI client 生成和 CI。
- 提供 Docker Compose：app、postgres+pgvector、可选 redis、nginx。

验收：现有 mock 工具对话行为未被意外破坏；应用启动、迁移、健康检查、前端 build、Testcontainers、Compose smoke 全通过。

## Phase 1：Mock Provider 的完整 Query Loop

2026-09-12 初版进度：Conversation/Message/Run/Event、并发幂等创建、终态 SQL CAS、Mock 结构化 tool call、工具 allow-list/基础 schema/read policy、maxTurns/deadline/取消检查/工具与估算 token budget、SSE replay 和 Console Playground 已形成闭环。Agent version/publish、RunStep/ToolCall 表、精确 token/cost、工具 timeout 和五页 Console 仍待完成。

- Provider/Model、Agent draft/version/publish、Conversation/Message/Run 数据模型。
- Mock model 支持文本回答和结构化 tool calls。
- ToolRuntime 实现 schema、risk、permission、timeout、result cap。
- 内置 `current_time`、`calculator`。
- SSE event、取消、幂等、终态 CAS、启动 orphan 收敛。
- Console 五页最小闭环。

验收：无外部 key 完成“模型 -> 工具 -> 模型 -> 最终回答”；所有终态和失败路径有测试。

## Phase 2：真实 Provider 与流式可靠性

进入自动路由前先保留一个不改变 Run 行为的 Intent Router 预览切片：`IntentDecision` 四出口契约、确定性规则、当前模型的结构化 JSON 候选、低置信/歧义/缺槽澄清和 100-200 条中文评测集。先跑 rule-only 与当前模型基线；只有混淆矩阵、成本和延迟证明必要，才增加动态 few-shot、向量候选召回或轻/深模型分层。预览与 shadow 达标前不允许自动执行 route。

- OpenAI-compatible adapter；tool call/result 原生格式映射。
- connect/read-idle/overall timeout、bulkhead、熔断、分类重试。
- usage/cost、首 token、结构化 trace；Nginx SSE 配置。
- 真实兼容服务的受控 contract test（凭证不进入 CI 日志）。

验收：partial stream、429、5xx、401、取消、断线 replay、应用重启均有证据。

## Phase 3：MCP

- MCP Server CRUD、连接测试、tools/list、schema snapshot、工具刷新。
- SSRF/DNS/redirect 防护、credentialRef、只读 allow policy。
- 至少一个真实只读 MCP 集成。

验收：正常调用、schema 漂移、超时、恶意 URL、超大结果、权限拒绝测试通过。

## Phase 4：简版 RAG

- TXT/Markdown 上传、checksum/version、分块、embedding、HNSW。
- 检索测试、ContextManager 注入、message citation。
- 索引任务持久化、重试和应用重启恢复。

验收：答案显示可点击引用；删除/重建、失败恢复和 prompt injection 边界通过测试。

## Phase 5：JSON Workflow

- 版本化 DSL 与 Start/LLM/Tool/Knowledge/Condition/End 节点。
- 静态校验、Run snapshot、统一预算/事件/错误。
- 不做循环、并行和可视化画布。

验收：分类 -> 分支 -> RAG/Tool -> 回答的完整流程可重放，非法图发布失败。

## Phase 6：上线门禁

- 密钥、网络、备份恢复、日志保留、资源限制和升级回滚演练。
- 100 并发 SSE 容量测试与 Provider 故障注入。
- 安全清单、许可证/品牌独立性检查。

验收：恢复演练和回滚成功；P0/P1 告警可触发；运维手册由另一位 reviewer 复核。

## Phase 7：可选的 Agent 长期记忆

本阶段借鉴 Hindsight 的 Retain/Recall/Reflect 思路，但不进入当前 MVP。只有在跨会话偏好、历史决策或重复排障已经成为可测痛点，且 Phase 6 的隔离、删除、审计和恢复能力通过后才启动。静态知识继续由 RAG 负责；数据库和外部业务系统仍是强一致事实源，Memory 只提供可追溯的历史证据与派生判断。

### 7.1 边界与接入

- 先写 ADR，再决定是否新增独立 `hify-memory` 模块；禁止把记忆抽取和更新塞入 QueryLoop。
- Conversation/Message/Run 保留原始事实；记忆索引是可重建派生层。
- ContextManager 通过 `MemoryRecallPort` 按任务需要取回 evidence；QueryLoop 只消费证据，不负责 Retain 或 Reflect。
- Memory Bank 按用户、项目、团队或 Agent 隔离；任何请求只能访问显式授权的 bank。
- 网络或索引故障时降级为无长期记忆的普通对话，不阻断核心 Chat。

### 7.2 Retain：先实现可治理的事实记忆

- 从对话、工单和任务结果异步抽取 Fact、Entity、关系、发生时间、来源与标签；不把整段聊天直接视为长期记忆。
- 第一版数据对象只覆盖 `memory_bank`、`fact`、`entity`、`relation`、`source_evidence`；Fact 保存 `observed_at/effective_from/effective_to/confidence/superseded_by`。
- 写入前完成脱敏、事实/推断分类、实体归一、重复合并和敏感级别判断；临时猜测、失败尝试和无来源结论默认不保留。
- 更新采用“新证据追加 + 旧事实失效/被取代”，不静默覆盖历史；支持纠错、删除、过期和重建。

### 7.3 Recall：分级建设多路检索

- 第一阶段使用语义、BM25/关键词和时间过滤；只有多跳业务评测证明必要时才增加关系图检索。
- 不直接混加向量分数、BM25 分数、图距离和时间分数；候选先归一为统一 memory evidence，再使用 RRF 融合。
- reranker 是可选精排层，必须受 timeout、候选数、token 和成本预算控制；超时可退化到融合结果。
- 每条返回证据包含 fact/entity、有效时间、来源引用、置信度和 bank；按上下文 token budget 裁剪。

### 7.4 Reflect：最后引入可演化判断

- 仅在冲突处理、偏好变化和方案取舍等复杂问题中调用；简单事实查询只走 Recall。
- Observation/Mental Model 必须保存 supporting facts、适用对象与场景、置信度、版本和更新时间；无来源判断不得持久化。
- 新证据可以强化、限定或推翻旧观点；回答需明确区分“事实证据”和“系统推断”。
- disposition 只影响推理与表达风格，不能改变事实、授权规则或安全边界。

### 7.5 治理与验收

- 权限：跨 bank 泄露测试为零容忍；Prompt、tool result 和历史记忆都按不可信输入处理。
- 删除：删除 source 后，相关 Fact、Observation、向量、关键词和关系索引可追踪清理或重建。
- 可解释：任何由记忆影响的答案都能回到 source evidence；UI 展示关键依据、新旧状态和判断置信度。
- 质量：建立跨会话偏好、时间冲突、多跳关系、历史决策和工单复盘评测集，记录 Recall hit rate/precision、answer success、conflict resolution accuracy 和 stale-memory error rate。
- 性能：分别记录 Retain、Recall、rerank、Reflect 的 P50/P95 延迟、token 与成本；主链路有超时和降级策略。
- 上线：先 shadow recall，再向内部测试用户显示证据，最后才允许 Reflect 影响回答；任一步质量或安全指标不达标即可关闭。

验收：长期记忆在目标评测集上相对“无 Memory”和“普通 RAG”基线有明确增益；不存在跨 bank 泄露；新旧事实冲突可解释；删除可验证；Memory 服务不可用时核心对话仍能完成。
