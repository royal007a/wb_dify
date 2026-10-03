# E1：关闭不是取消

使用每个应用上下文独立的 stopping 信号。关闭先禁止新的执行/工具活动；用户持久化取消保持优先。进程中断不结算 agent_run 为 CANCELLED/EXECUTOR_REJECTED；保留 RUNNING 供下次启动重新接续 checkpoint。Workflow 的一次执行与节点用 INTERRUPTED 区分进程中断，新增迁移只扩展允许状态。

步骤：用独立上下文和阻塞模型/检索夹具复现；修复生命周期与终态边界；重启同一隔离库验证恢复；对恢复扫描逐行异常隔离；回归原取消、拒绝、重复调度和 PostgreSQL 迁移。

不操作 hify-cc、132 或共享服务/凭据。单实例先停后启。恢复不是任意副作用重放或数据库回滚；已有工具幂等/执行资格约束仍适用。Workflow 与 agent_run 取消竞争的最终投影协调另属 SPEC-RUN-ADMISSION-001，不在本片宣称完成。

验收保存真实红绿日志；实际关闭不能依赖测试修改生命周期标记。数据库暂不可用时允许中断留痕写入失败，不能编造终态；下次扫描应继续处理其他行。kill -9 无法保证关机事件，启动需收敛旧 Workflow RUNNING 记录为 INTERRUPTED。
