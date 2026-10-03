# Workflow 与 AgentRun 最终提交竞争

原 B2/E 分解后的剩余范围。容量拒绝、进程内调度所有权、应用关闭恢复分别见 SPEC-RUN-ADMISSION-002、SPEC-RUN-DISPATCH-001、SPEC-RUN-SHUTDOWN-001；状态以 tasks.json 为准。

仅当前仓库，先红灯复现、再最小修复、独立提交后复验。不操作hify-cc、共享服务或真实凭据。本文不是任务状态真源。

验收：
- 在 Workflow 保存完成与 AgentRun 终态锁之间用 latch 提交取消，明确两处状态和 SSE 投影的一致语义。
- 不将后到取消/超时随意覆盖先发生的真实业务失败；先定义优先级再实现。
- Workflow 投影不能因父 Run 改为 CANCELLED 而只剩 started；预取消事件也不能重复或缺 terminalReason。
- 用模块 application port 保持依赖边界；不由 chat 直接访问 Workflow repository。

结算契约：WorkflowRun 记录这一次执行的事实结果，不因父 Run 稍后取消而把已成功/已失败的历史改写。AgentRun 记录是否向用户交付；父行锁下已持久取消仍优先并抑制助手消息。每个返回的 Workflow 结果均产生相应 workflow.* 事件，携带 executionState、runState、runTerminalReason、assistantCommitted；与父终态在同一事务提交。因此 SUCCEEDED + CANCELLED 是“执行成功但回复取消”，不是伪称两者状态相同。

节点异常在首次观察时按当时控制状态分类；已经分类为业务 FAILED 后，不靠保存期间或结束时读取最新取消/期限标志抹掉原因。这不承诺业务异常总是优先于首次观察时已可见的停止。已完成 Future 的异常先读取，仍在阻塞的调用继续受控制轮询和取消保护。无数据库错误时，对账信息完整；数据库失败的恢复与跨实例保证不在本片扩展。

证据保留失败与通过命令，记录源码提交。回滚仅本切片代码，不回滚数据库历史或外部副作用。未复验的静态意见不可标成已证实缺陷。
