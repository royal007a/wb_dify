# Workflow 结算复核证据

任务 SPEC-RUN-ADMISSION-001；基线 830e0be。源码版本以本文件所在代码提交及 harness verification.json 为准。没有部署、没有访问共享数据库或真实凭据。

证据目录：`harness/evidence/SPEC-RUN-ADMISSION-001/SPEC-RUN-ADMISSION-001-20261003T202045Z-26dafb80/`。

- red-settlement.log：4 项全部断言失败、0 error、0 skip，旧实现丢取消竞争的 Workflow 投影、缺 executionState，真实 Workflow 失败误归 MODEL_ERROR。
- red-control.log：11 项中 3 项断言失败、0 error、0 skip，旧实现晚到取消/期限覆盖已观察业务失败，以及完成的失败 Future 被取消掩盖。
- green-pg-settlement.log：43 项通过、0 skip：WorkflowControl 11、WorkflowEngine 6、RunWorkflowControl 12、H2 结算 4、真实 PG16 结算 4、关闭上下文 6。此次在 Testcontainers 独立数据库运行，不用共享服务；SELECT version() 断言防止误用 H2。
- 最终原子门禁另见本目录 command.log / verification.json，包含后续 v2 payload 断言与 migration 非跳过门禁。

测试在真实执行结果保存之后用 latch 注入取消；正常/取消的成功路径还在 projection 写入后暂停事务，用第二连接验证父状态、助手消息、两类事件尚不可见。之后释放事务并验证 HTTP SSE 重放与数据库一致。不是只对 mock 调用次数作断言。

本片区分执行事实与交付：SUCCEEDED + CANCELLED 是可解释的组合，不是把两张表强行写成同一个值。正常失败现为 WORKFLOW_ERROR；投影 v2 同时写两处状态。首次观察时已存在的停止仍按原控制分类；不宣称每一种异常都比取消优先。

未验证：完整前端/全仓回归、线上部署、数据库故障的投影修复、分布式 lease，以及 reviewer 新发现的 model:N 已提交而 checkpoint 落后的恢复问题。后者单列 SPEC-HISTORY-RECOVERY-001，不因 E1 两条原 P1 关闭而视作解决。
