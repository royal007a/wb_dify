# ADR-0008：Agent 归档与工具快照

- 状态：Accepted
- 日期：2026-09-19

## 背景

Agent 已采用“草稿 -> 不可变发布版本 -> Conversation 固定版本”的运行模型。如果直接物理删除 Agent，或让发布版本继续读取草稿工具列表，会破坏历史会话的可重放性。原实现还把工具名保存在逗号字符串中，无法建立唯一约束、批量查询和独立更新契约。

## 决策

1. DELETE 采用归档：保留 Agent 与发布版本，写 `archived_at`，删除草稿工具绑定并阻止新会话。
2. 名称在归档后仍保留，数据库对 `agent_definitions.name` 建唯一约束，不允许复用。
3. 草稿工具写入 `agent_tool_bindings`；每次发布复制到 `agent_version_tool_bindings`，并把排序后的工具名纳入 digest。
4. 工具变更使用独立全量替换接口；基本信息 PUT 不隐式修改工具。
5. 绑定前通过 tool 模块公开的 `ToolCatalog` 校验，Agent 模块不引用 ToolRuntime 内部实现。
6. 草稿查询不缓存；发布与归档只失效 current 快照，已发布 version 快照保持不可变。

## 后果

- 旧 Conversation/Run 在 Agent 归档后仍可读取其固定版本，新 Conversation 被拒绝。
- 归档名称不能重用，换取简单、确定且可审计的唯一性语义。
- 发布事务多写一组版本工具行，但运行时无需读取可变草稿。
- MCP 上线时可把 tool identity 扩展为稳定 ID + schema version，而不改变发布快照原则。
