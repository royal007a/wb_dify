# Knowledge 原始 memory 交付边界

## 问题与规则

model:N 必须在后续工具或来源检查前提交 canonical history，以便崩溃后重放同一次模型响应；该事实不代表模型正文已通过交付核验。旧索引器会立即把它投影到 memory API，绕过正文 delta/assistant 消息门禁。

本片采用保守的 Run 级隔离，不引入“所有历史文本已验证”的误导标记：

- 不可变 AgentVersion 有知识绑定，或持久 checkpoint 存在知识门禁 Claim，就不开放该来源 Run 的原始 memory；不是检查调用方有没有绑定。版本/上下文无法读取或格式损坏时拒绝开放。
- 新增索引跳过这类 Run，但 canonical history 和恢复数据照常提交，不删除/改写已有记录。
- GET memory 返回空 catalog/summaries。search 逐来源 Run 过滤，H2 与 PG 路径相同；旧索引或同会话另一 Run 都不能绕过。detail（包含 history.detail 工具）在读正文前返回 409/CONFLICT，不回显正文。
- layered memory 不使用其已有摘要目录；模型的内部恢复上下文仍可用，不能将“内部上下文仍含原文”称为对用户交付。
- 完成后也不解封整个 raw memory。已经合格的最终答案由 Run/chat_messages 交付，普通未绑定知识的聊天仍支持原有 memory 行为。
- 这是功能收窄：知识 Run 的 raw history recall 暂不可用，不伪称只隐藏了一条最终回答。以后逐条释放需要持久化逐条交付/核验状态，不能只凭 Run COMPLETED 放行整个历史。
- 既有无版本、无 checkpoint 门禁标记的历史无法证明曾使用知识门禁；不新增历史数据推断或回填。当前知识主链在写 model 历史前已持久化门禁 checkpoint。

## 关闭

canonical 回读直接抛 ExecutionSuspendedException 时必须原样传播；若普通读异常发生在 stopping 期间，ExecutionControl 也会传播挂起。两者均保留 RUNNING + run.interrupted，不转 NEEDS_INPUT/FAILED、不交付正文。此集成测试模拟关闭信号/异常，不替代 SPEC-RUN-SHUTDOWN 的真实上下文销毁顺序测试。

## 验收

- KnowledgeMemoryBoundaryTest（PG 同套 KnowledgeMemoryPostgresTest）：缺引用 NEEDS_INPUT、来源校验 latch 中的 RUNNING、成功后均不可从 raw memory 读取；真实 canonical 行仍存在；人为恢复旧 DetailRef/ContextSummary 后读保护仍成立；去掉 pinned version 的旧 checkpoint、同会话无绑定 caller 也不能绕过。
- KnowledgeAdmissionShutdownTest（PG 同套 KnowledgeShutdownPostgresTest）：入场检索/最终 canonical 回读的显式挂起与 stopping+读异常，共四种组合；无正文泄漏。
- ContextMemoryIntegrationTest、HistoryReplayTest：普通 memory、canonical 重放兼容。
- 不新增迁移和 HTTP 路由；migration scope 追加 PG 检查且要求 0 skip。证据见 `../evidence/SPEC_KNOWLEDGE_MEMORY.md`。

来源存在不代表语义真实，本片不改变 SOURCE_REFERENCES_ONLY 的含义；前端核验提示、基础设施失败不应伪装澄清、部分检索成功事件口径及旧未固定 Agent 版本的恢复门禁另行处理。
