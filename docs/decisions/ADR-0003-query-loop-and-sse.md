# ADR-0003：代码强制的 Query Loop 与 SSE

- 状态：Accepted
- 日期：2026-09-08

## 背景

Agent 的主要风险不是普通 CRUD，而是无限循环、工具副作用、模型长连接、取消竞态和部分流失败。把这些写进 prompt 无法形成可靠保证。

## 决策

采用 `ConversationManager -> QueryLoop -> ToolRuntime` 边界。模型决定是否继续调用工具；代码强制 schema、权限、预算、超时、取消和终态。使用供应商原生结构化 tool call，不解析自由文本 Action。前端通过 SSE 接收版本化事件，PostgreSQL 保存最终消息、Run、step 和终态。

HTTP 层优先 Spring MVC + `SseEmitter` 与独立有界 executor，避免为了 20-50 人规模引入端到端响应式编程。Provider adapter 可使用适合其 SDK 的异步客户端，但 Reactor 类型不能泄漏到 domain/application port。

## 后果

一期不支持并行工具、子 Agent 和崩溃后继续执行中的模型流。应用重启时遗留 Run 明确收敛为 INTERRUPTED；浏览器断线只重放已保存事件。

## 重新评估触发器

若负载测试证明线程/连接模型成为主要瓶颈，且团队能承担响应式复杂度，再以 ADR 评估 WebFlux；不能只凭“流式就该响应式”迁移。

