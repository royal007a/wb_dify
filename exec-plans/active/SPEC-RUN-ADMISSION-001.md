# Workflow 与 AgentRun 最终提交竞争

原 B2/E 分解后的剩余范围。容量拒绝、进程内调度所有权、应用关闭恢复分别见 SPEC-RUN-ADMISSION-002、SPEC-RUN-DISPATCH-001、SPEC-RUN-SHUTDOWN-001；状态以 tasks.json 为准。

仅当前仓库，先红灯复现、再最小修复、独立提交后复验。不操作hify-cc、共享服务或真实凭据。本文不是任务状态真源。

验收：
- 在 Workflow 保存完成与 AgentRun 终态锁之间用 latch 提交取消，明确两处状态和 SSE 投影的一致语义。
- 不将后到取消/超时随意覆盖先发生的真实业务失败；先定义优先级再实现。
- Workflow 投影不能因父 Run 改为 CANCELLED 而只剩 started；预取消事件也不能重复或缺 terminalReason。
- 用模块 application port 保持依赖边界；不由 chat 直接访问 Workflow repository。

证据保留失败与通过命令，记录源码提交。回滚仅本切片代码，不回滚数据库历史或外部副作用。未复验的静态意见不可标成已证实缺陷。
