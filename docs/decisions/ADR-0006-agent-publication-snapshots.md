# ADR-0006：Agent 草稿与不可变发布快照

- 状态：Accepted
- 日期：2026-09-13

## 背景

AgentDefinition 原型是一行可变配置，Conversation 只保存 agentId。编辑 prompt、模型或工具后，旧会话会在下一次 Run 中无审计地改变行为。

## 决策

保留 `agent_definitions` 作为迁移期草稿表，发布时在 `agent_versions` 写入不可变规范化快照和 SHA-256 digest，并在同一事务更新 published pointer。Conversation 创建时固定 agentVersionId；Run 再记录 versionId/digest，执行时只读该版本。Provider 与 modelId 必须在保存和发布时有效。

## 后果

- 草稿编辑不会改变历史会话；运行结果可追溯到版本与摘要。
- 发布是显式动作；未发布 Agent 不能创建会话。
- 当前表名仍保留原型遗产，后续 JPA→MyBatis-Plus 迁移时可重命名为 `agents`，但不得破坏外部 ID 和版本绑定。
- 当前 tool 列表随版本固化；完整 tool schema snapshot 在 MCP 阶段补齐。
