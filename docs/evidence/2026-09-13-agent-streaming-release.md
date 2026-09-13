# Agent 发布与原生流式交付证据

日期：2026-09-13

## 自动验证

- `mvn -q -f backend/pom.xml test`：71 tests，0 failures，0 errors，1 skipped。跳过项为 Testcontainers PostgreSQL 测试；测试进程未发现标准 Docker socket。
- `NativeProviderModelClientTest`：OpenAI SSE 文本与分片 tool arguments、Anthropic text_delta、Gemini streamGenerateContent 均通过。
- `AgentApiIntegrationTest`：发布 v1、会话钉住 v1、草稿更新并发布 v2 后，旧会话 Run 仍使用 v1 versionId/digest。
- `npm run build`：Vue TypeScript 检查与 Vite production build 通过；保留单 bundle 超过 500 KiB 的非阻塞告警。
- Skill validator：`.codex/skills/hify-module-delivery` 有效。

## PostgreSQL 与部署验证

- `./deploy/up.sh` 构建后端/前端镜像并启动 `hify-postgres/hify-app/hify-web`。
- 复用已有 PostgreSQL volume，从 V5 成功执行 V6；`flyway_schema_history` 最新成功版本为 `6`。
- `GET http://127.0.0.1:8088/api/v1/health` 返回 HTTP 200 和 `Hify is running`。
- `GET /api/v1/agents` 返回已发布的 `demo-agent-v1`。
- 创建 Conversation 后运行“计算 7*8”：Run 终态 `COMPLETED`，toolCalls=1，输出 56，并返回 agentVersionId/digest。
- 持久事件顺序包含 run/plan/checkpoint/model、tool started/completed、context/continuation、`message.delta`、`run.completed`。

## 未完成/环境边界

- 当前环境没有可用的 CUA 浏览器 surface，未做浏览器像素级页面验收；已用 production build 与 Nginx HTTP 部署验证前端产物。
- 尚未使用真实供应商凭证验证公网流；本轮用本地协议服务器做确定性 contract test。
- 流式 429/5xx/半途断流、首 token/usage/cost 仍在下一验证切片。
