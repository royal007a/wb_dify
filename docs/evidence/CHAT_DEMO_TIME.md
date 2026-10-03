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

## 132 部署验收

- 发布基线 `208bdc3`（运行逻辑 `700aafd`，前端 `9e29e9c`），入口 `https://118.196.123.132/hify/chat`。
- 备份位于 `/opt/hify/releases/chat-demo-20261003-208bdc3/previous.jar` 和 `previous-dist/`。
- 本地/远端 jar SHA256 一致：`592344590c678ef5439ac4035ff6b213abb12c783a865680ffea652c698f792f`。
- 健康接口返回 HTTP/业务 200；hify-cc 六个容器继续 healthy，未操作。
- 公网真实浏览器覆盖同一 Demo 会话 hi → 现在几点 → 现在几点？ → 现在几点?：每个时间请求都产生 current_time 事件，终态为 COMPLETED、2 turns、1 tools，输出真实带 +08:00 偏移的时间。另验证 calculator=391。
- 首次浏览器运行 4 通过、1 失败：新增用例点击 select 外容器没有展开菜单，尚未发送时间问题。改为等待已选中的 Demo 标签（不绕过回答/工具断言）后重跑五项回归，5/5 通过（16.3s）。
- 浏览器命令：`E2E_BASE_URL=https://118.196.123.132/hify/ E2E_IGNORE_HTTPS_ERRORS=true npx playwright test e2e/chat.spec.ts e2e/chat-time.spec.ts e2e/mcp-edit.spec.ts e2e/management.spec.ts --workers=1`。
- TLS 忽略仅限测试浏览器识别当前自签名证书，不改应用出站验证。测试使用新会话，不清理或重写用户旧会话。
