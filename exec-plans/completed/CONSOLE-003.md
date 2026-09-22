# CONSOLE-003

在 CAPABILITY-002 的不可变 Runtime 契约通过后，交付使用同一 Workflow DSL 的可视化控制台。

1. Workflow 列表进入大尺寸画布，不引入第二套前端 DSL。
2. 节点面板支持现有确定性节点；画布支持拖放、连线、选择和删除。
3. 属性面板直接编辑节点配置；保存后必须调用后端 validate。
4. 试跑只使用 publishedVersionId，版本 Diff 对比草稿和最新发布快照。
5. Agent 表单增加 Workflow 单选与 MCP READ 工具多选，并沿用草稿绑定 API。
6. 用 Vue 类型检查、Vite 生产构建和 Playwright 管理台回归验收。
