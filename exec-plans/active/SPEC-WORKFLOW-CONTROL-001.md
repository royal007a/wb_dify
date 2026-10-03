# Workflow 取消与预算

针对A02，不处理A03语料冻结或新增编排器。复用ExecutionControl，Run总预算从创建计时，不能重新开始。

1. 建立阻塞检索latch反例；取消后等待方和worker都退出，下一节点不得开始。超时、预取消、排队取消独立测。
2. WorkflowCapabilityPort接收同一控制对象，节点前后检查。阻塞只读调用使用有界、拒绝而非CallerRuns的专用执行池；finally取消Future。
3. 不在等待外部/子线程工作时持有整条Workflow的数据库事务。节点状态分别持久化，取消/超时是可区分终态；这不是通用回滚。
4. Chat映射Workflow CANCELLED/TIMED_OUT，不允许当作MODEL_ERROR或COMPLETED。成功提交时再次检查持久取消状态，覆盖取消与完成竞态。
5. 定向模块、服务与HTTP回归；全量PG/真实Provider矩阵仍由SPEC-VERIFY负责，不提前部署。
