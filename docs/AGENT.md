# Agent 管理与发布

## 1. 产品边界

Agent 是“身份指令 + Provider 模型 + 运行参数 + 工具集合”的可发布配置。管理端编辑草稿；运行端只读取不可变发布版本。知识库、MCP Server 管理和多 Agent 编排不在本切片内。

## 2. 归档语义

- `DELETE /api/v1/agents/{agentId}` 表示归档，不物理删除 `agent_definitions`。
- 归档写入 `archived_at`、关闭草稿并删除草稿工具绑定；名称永久保留，不能被新 Agent 重用。
- 归档后的 Agent 不出现在列表中，不能查看、修改、发布或创建新 Conversation。
- `agent_versions` 和版本工具快照不删除；已经固定 `agent_version_id` 的 Conversation/Run 继续按旧快照执行。
- 本期不提供恢复归档接口；如需恢复，必须先补审计与名称冲突语义。

## 3. 工具绑定与发布快照

- 创建 Agent 时可携带初始 `enabledTools`，与草稿在同一事务写入。
- 后续工具变更只走 `PUT /api/v1/agents/{agentId}/tools`，请求体为完整 `toolIds` 集合；基本信息 PUT 不再修改工具。
- 工具必须存在于 `ToolCatalog`，重复、未知或超过 32 个均拒绝。
- `GET /api/v1/tools` 暴露只读管理投影（稳定 id、展示名、描述、来源、风险、可用状态）；前端不得硬编码工具列表。
- `agent_tool_bindings` 是草稿工具事实源；`agent_version_tool_bindings` 是发布版本的不可变快照。
- 发布 digest 包含排序后的工具集合。草稿换工具不会改变既有版本；再次发布才生成新快照。
- Console 的 `hasUnpublishedChanges` 来自草稿运行配置 digest 与当前发布 digest 的精确比较，不用 draftRevision/versionNo 猜测；纯管理描述不属于运行差异。

## 4. 参数与数据库约束

- name 1-100、description 最长 2000、instructions 1-8000。
- providerId/modelId 必填；模型必须属于启用 Provider 且已启用。
- temperature 为 0-1，maxTokens 1-32768，maxTurns 1-20，maxContextTurns 1-100。
- `agent_definitions.name` 有数据库唯一约束，Service 预检查只负责友好错误，并发正确性由数据库兜底。

## 5. 查询与缓存

- Agent 列表一次分页查询后，批量读取发布版本号与草稿工具绑定，禁止逐 Agent 查询。
- 草稿详情和列表不缓存。
- `agent-cache` 只缓存 `current:{agentId}` 发布指针快照和 `version:{versionId}` 不可变快照。
- 修改草稿或草稿工具不驱逐运行缓存；发布和归档只驱逐对应 `current:{agentId}`，既有版本缓存继续有效。

## 6. Console 验收

- 列表展示工具数、temperature、草稿 revision、发布同步状态、启用状态和创建时间。
- 创建/编辑弹窗动态读取 Tool Catalog；编辑保存分别调用基本信息与工具绑定接口。
- 归档必须二次确认，并明确“阻止新会话、保留历史版本”；成功后刷新列表。
- 发布状态只有三种：未发布、当前版本有草稿变更、当前版本已同步。
