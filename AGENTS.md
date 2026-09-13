# AGENTS.md - Hify 项目地图与硬规则

Hify 是面向内部 20-50 人的本地 AI Agent 平台。当前优先完成可运行纵向闭环，不追求 Dify 的功能广度。详细设计见 `docs/`；本文件保持短小，只放地图与不可破坏规则。

## 必读地图

- 产品范围：`docs/SPEC.md`
- 当前实现边界：`docs/CURRENT_STATE.md`
- 初版交付证据：`docs/INITIAL_RELEASE.md`
- 架构与 Query Loop：`docs/ARCHITECTURE.md`
- 意图路由与评测：`docs/INTENT_ROUTING.md`
- Replan 与 Try/Confirm/Cancel：`docs/REPLAN.md`
- Provider 设计与交付：`docs/PROVIDER.md`
- API：`docs/API.md`
- 数据：`docs/DATA_MODEL.md`
- 工程：`docs/ENGINEERING.md`
- 安全：`docs/SECURITY.md`
- 运维：`docs/OPERATIONS.md`
- 执行顺序：`docs/IMPLEMENTATION_PLAN.md`
- 原型对齐：`docs/PHASE_0_ALIGNMENT.md`
- 重要决策：`docs/decisions/`

## 代码地图（目标态）

- `backend/hify-app`：启动、装配、HTTP/SSE transport
- `backend/hify-provider`：模型提供商、模型适配器、凭证引用
- `backend/hify-agent`：Agent 定义、版本、发布配置
- `backend/hify-chat`：会话、消息、Query Loop、Run、流事件
- `backend/hify-tool`：内置工具、权限与执行
- `backend/hify-mcp`：MCP 客户端、Server 配置与工具发现
- `backend/hify-knowledge`：文档、分块、embedding、检索、引用
- `backend/hify-workflow`：JSON 线性/条件工作流
- `backend/hify-demo`：MyBatis-Plus CRUD 参考切片，不属于产品域
- `backend/hify-common`：业务无关基础类型；禁止成为杂物间
- `frontend/`：Vue 3 管理台和 Playground

当前已建立 Maven 多模块 reactor，并将已实现代码迁入对应模块；`hify-knowledge`、`hify-workflow`、`hify-mcp` 目前仅有明确空壳，不能把目录存在误报为业务已实现。开始改造前先读 `docs/CURRENT_STATE.md`。

## 生成约束

- 大任务按“地基 → 框架 → 验收”拆解；每步输出必须在一次可 review 范围内。
- 父 POM 的 modules 必须与目录一一对应；版本只由父 POM/BOM 管理，子模块禁止重复版本。
- 异常响应必须使用 `Result.fail()` 和 `ErrorCode`；禁止硬编码错误码或错误文案。
- 空壳模块只建立约定目录和 module marker；没有验收用例前禁止生成推测性 CRUD。

## 产品边界

- 一期必须：Provider、Agent、Chat、Tool/MCP、Console、可观测 Query Loop。
- 一期简化：RAG 只支持 TXT/Markdown + 固定/递归分块；Workflow 只支持 JSON 线性与条件分支。
- 一期不做：多租户、计费、插件市场、微调、可视化画布、分布式执行、子 Agent、公开 WebApp。

## 架构硬规则

- 模块化单体、单向依赖；跨模块只调用公开 application port，不引用对方 Repository、Entity 或 internal 类。
- Controller 只做鉴权、校验、协议映射；事务和业务规则属于 application/domain service。
- PostgreSQL 是唯一业务事实源；Redis 失败不得造成持久数据丢失或错误终态。
- 外部模型/MCP/HTTP 调用必须设置 connect/read/overall timeout、bulkhead、可观测错误和取消传播。
- 不在已经向客户端输出 token 后自动重试整个模型请求，避免重复答案和重复副作用。
- 数据库变更只新增迁移；索引和约束必须与主查询、幂等和唯一性一起设计。

## Query Loop 硬规则

- 使用供应商原生结构化 tool call；不解析自由文本 `Action:`。
- 固定执行顺序：模型响应 -> 提取 tool calls -> schema 校验 -> 权限检查 -> 执行 -> 限长 tool result -> 下一轮模型。
- 必须有 `maxTurns`、wall-clock timeout、token/cost/tool-call budget、取消和明确终止原因。
- 权限检查发生在副作用前；危险工具默认拒绝或要求显式批准。
- tool error 可恢复时作为 `tool_result` 返回；安全违规、缺凭证、重复失败和预算耗尽直接终止。
- Replan 必须分别记录失败点、根因点、回滚点和重计划起点；不得用 Replan 绕过权限或重置预算。
- Query Loop 的控制出口只允许 `CONTINUE/FINISH/CLARIFY/RETRY/REPLAN/INTERRUPT`，并持久化 reason 与状态引用。
- FINISH 必须通过代码门禁：最终回答存在、无 blocking Gap、required Claim 有 VERIFIED evidence；模型不得自行宣布成功。
- Retry 必须受独立次数和全局预算限制；重复语义状态没有新增证据时必须停止机械循环。
- read 工具可在 Try 中执行；write/execute/external 工具必须先有精确确认与副作用账本，禁止把 checkpoint 宣称为外部副作用回滚。
- ConversationManager 持有持久会话；QueryLoop 只持有单次 run；ToolRuntime 持有注册、schema、权限和执行。
- 上下文裁剪/摘要属于 ContextManager，不塞进 QueryLoop。

## Provider 硬规则

- 管理 API 只开放 OpenAI、Anthropic、Gemini 和 OpenAI-compatible；Mock 仅供开发测试。
- 鉴权 JSON 只能保存 credentialRef 和协议元数据，禁止保存 token、API Key 或 Authorization 值。
- 模型 displayName 与供应商 modelId 分离；健康状态独立写入，不得污染 Provider 配置缓存。

## Intent Router 硬规则

- 出口只允许 `unknown/clarify/tool/workflow`；意图判断不得直接执行副作用。
- 取消、帮助、危险动作、精确命令和必填槽位优先走确定性规则；危险动作必须追问或批准。
- 模型只提供结构化候选；低置信、Top2 接近、缺槽或非法结构由代码降级为 `clarify/unknown`。
- 未经离线评测和 shadow 验证，Intent Router 不得接管 Run 主链路；优化必须同时报告质量、延迟、成本和安全。

## 完成定义

- 代码、测试、API/事件契约、迁移和文档一致。
- happy path、超时、取消、预算耗尽、工具失败、权限拒绝均有测试。
- 日志和事件不泄露 API Key、Authorization、Prompt 中的秘密或原始工具凭证。
- 变更未越过 `docs/IMPLEMENTATION_PLAN.md` 当前阶段；越界先更新决策和验收标准。
