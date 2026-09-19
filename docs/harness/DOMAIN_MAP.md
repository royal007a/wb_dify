# Harness Domain Map

| 领域 | 代码/资产 | 默认验证范围 | 高风险边界 |
|---|---|---|---|
| Common | `backend/hify-common` | backend | HTTP、缓存、线程池公共行为 |
| Provider | `backend/hify-provider` | backend,runtime | 凭证使用、出站网络、真实模型成本 |
| Agent | `backend/hify-agent` | backend,runtime | 发布版本不可变、工具快照 |
| Chat/Run | `backend/hify-chat` | backend,runtime | SSE、取消、终态、checkpoint |
| Intent Eval | `hify-chat/com.hify.intent`、评测 TSV | eval,runtime | route 不得越过权限和执行边界 |
| Tool | `backend/hify-tool` | backend,runtime | write/execute 需确认与副作用账本 |
| MCP | `backend/hify-mcp` | backend,runtime | 当前空壳；网络与凭证均属高风险 |
| Knowledge | `backend/hify-knowledge` | backend,migration | 当前空壳；原文、隔离与删除 |
| Workflow | `backend/hify-workflow` | backend,runtime,migration | 当前空壳；分支与恢复 |
| API/装配 | `backend/hify-app` | backend,migration | Flyway、Controller、PostgreSQL |
| Console | `frontend` | frontend | 构建通过不等于浏览器 E2E 通过 |
| Harness | `harness`、`exec-plans`、`docs/harness` | harness | 状态、权限和证据完整性 |

`verify.sh --scope auto` 根据变更文件选择范围；高风险或跨域任务必须在 `tasks.json.verifyScopes` 显式扩大范围，不得依赖自动推断缩小验证。
