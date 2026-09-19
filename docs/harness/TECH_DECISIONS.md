# Active Technical Decisions

## Harness 决策

| 决策 | 当前选择 | 原因/后果 |
|---|---|---|
| 状态真源 | `harness/tasks.json` | 消除 Markdown、会话和 Agent 各自维护状态的漂移 |
| 当前运行 | `harness/state.json` | 将 baseline、checkpoint、evidence 跨会话保存 |
| Progress | 机器生成并校验 | 人类可读但不形成第二真源 |
| 并发 | 单任务目录锁 | 当前单仓单工作区；不伪装支持安全并行写 |
| 权限 | operation→risk 白名单 | 未声明操作默认高风险；禁止动作不能靠审批绕过 |
| YAML 解析 | JSON 语法的 YAML 1.2 | 免 PyYAML 运行依赖，保持机器可读 |
| 验证 | 范围矩阵 + 非零退出码 | 自动推断只作便利，任务显式范围决定 DoD |
| Evidence | JSON manifest 版本化、日志忽略 | 兼顾可审计与泄密/仓库膨胀风险 |

## 产品/架构 ADR 索引

- 模块化单体：`../decisions/ADR-0001-modular-monolith.md`
- PostgreSQL：`../decisions/ADR-0002-postgresql-pgvector.md`
- Query Loop/SSE：`../decisions/ADR-0003-query-loop-and-sse.md`
- 凭证和出站：`../decisions/ADR-0004-secrets-and-egress.md`
- Provider：`../decisions/ADR-0005-provider-catalog-and-health.md`
- Agent 不可变发布：`../decisions/ADR-0006-agent-publication-snapshots.md`
- 原生 Provider 流：`../decisions/ADR-0007-native-provider-streaming.md`
- Agent 归档/工具快照：`../decisions/ADR-0008-agent-archive-and-tool-snapshots.md`

当 Harness 的状态模型、授权语义或 evidence 保留策略发生不兼容变化时，新增正式 ADR，不在本索引中直接覆盖历史理由。
