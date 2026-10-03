# SSE committed-event contract

针对A07。保持现有Run/事件模型，不增加第二条流或消息队列。

1. 先用受控SseEmitter与事务同步反例证明：回滚不能发送，终态行先于事件时不能提前关闭，订阅和commit交错不得重复/漏事件。
2. publish在Run行锁下分配序号，commit后从已提交事件追赶订阅游标；本地锁使用固定条带，不能在complete时删除形成双锁。
3. 把Run终态、助手消息、terminal事件放在同一事务；NEEDS_INPUT保留decision/gapIds。Workflow前置事件先写、terminal最后；重启收敛也必须产生terminal事件。
4. 用真实PostgreSQL验证回滚、并发publish序号和terminal事务；复用API/SSE replay用例。前端受控测试不是后端事务证据。
5. 更新事件契约及失败证据，不把DB提交后的客户端收包称为exactly-once；SSE支持按id重放和客户端去重。
