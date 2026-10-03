# Demo 会话时间查询修复（2026-10-03）

来源：Lark om_x100b633b0e70b0a0b257a783089a8e3。范围：原 Hify，不修改 hify-cc。

## 根因与修复

MockModelClient 只匹配“时间”或英文 time，但 UI/模型兜底文案推荐“现在几点？”。该推荐问句没有进入结构化工具调用，错误回显并显示 0 tools。不是 MCP 故障，线上 Demo 已绑定 current_time/calculator。

- 补中文“几点/日期/几号”及标点变体；英文 time 按单词匹配，避免 timeout/runtime 误触发。
- 继续检查当前工具列表，不绕过 ToolRuntime 的 schema、权限、lease 或 FinishGate。
- 兜底明确“本地规则模拟，未调用真实大模型”，只推荐已授权的演示工具。
- 前端欢迎语不再让所有 Agent 冒充 Demo，明确通用问答需配置真实模型。
- 未更改 Agent 发布版本、历史消息、Provider 配置、MCP 配置或凭证。

## 验证

- 先补 13 个 MockModelClient 测试，旧实现 9 个失败；修复后 13/13 通过。
- RunFlowIntegrationTest 2/2：同一 conversation 内 hi → 现在几点 → 现在几点？ → 计算 12.5 * 4 → 现在几点?；非问候各调用 1 次工具，输出时间/50，保持相同 agentVersionId，有工具和终态事件。
- 显式 Harness backend/frontend/harness 门禁通过；后端 135 个用例，132 执行通过、3 个 PostgreSQL 并发测试默认跳过。无数据库迁移。
- 前端类型检查和生产构建通过，仍有既有 bundle size 警告。
- 浏览器回归新增 `frontend/e2e/chat-time.spec.ts`，包含连续提问和 SSE/终态展示校验。

工具返回的时间带 ISO-8601 时区偏移；不伪造时间、不调用收费模型。历史错误回复不重写，新发消息使用修复后的代码。
