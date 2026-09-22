# CAPABILITY-002

按不可变运行契约推进：Agent 草稿只保存选择，发布时分别解析并冻结 WorkflowVersion 和 MCP tool snapshot；Chat 永远从 AgentVersion 读取快照，不读取可变草稿。

执行顺序：

1. V19 建立草稿绑定与发布快照表，并补数据库唯一约束。
2. Workflow 暴露 published snapshot / exact-version execute 端口；Agent 发布冻结 versionId/checksum。
3. MCP 暴露 current READ snapshot / exact-revision execute 端口；Agent 发布冻结 serverRevision、schemaDigest 与 tool schema。
4. ToolRuntime 支持由 AgentVersion 注入的动态 ToolDefinition，并在调用时经 MCP executor 执行；仍拒绝 WRITE/EXTERNAL。
5. Chat 按固定 AgentVersion 分流：绑定 Workflow 时执行确定性图；否则继续 QueryLoop，并注入冻结 MCP capability。
6. 用旧版本稳定性、schema 漂移拒绝、取消/lease 二次校验和事件序列测试验收。

一期边界：一个 Agent 最多绑定一个入口 Workflow；Workflow 仅支持现有确定性节点；MCP 只开放 READ。可视化画布在此运行契约通过后另建任务。
