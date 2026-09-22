# CAPABILITY-001

按依赖顺序交付 Knowledge 纵向绑定：先建立 corpus revision 与稳定检索端口，再增加 Agent 草稿绑定和发布快照，随后在 Chat Query Loop 之前注入固定 revision 的 citation context，最后接 Agent Console、迁移与端到端测试。

不可变性边界：canonical history 和 document chunk 是事实源；AgentVersion 只引用发布时冻结的 corpus revision。知识库后续新增或归档文档，不得改变历史 AgentVersion 的运行结果。

运行时边界：Knowledge retrieval 属于 Context Manager 输入准备，不进入 QueryLoop，也不伪装成 Tool；检索失败产生明确事件但不绕过取消、预算和终态协议。

一期不做：Workflow/MCP 绑定、向量模型外部化、图检索、reranker、可视化 Workflow 画布。
