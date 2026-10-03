# 已提交操作恢复证据

任务 SPEC-HISTORY-RECOVERY-001；基线 20e9667。源码版本见本文件所在代码提交和 harness verification.json；未部署、未访问共享服务或真实密钥。

目录：`harness/evidence/SPEC-HISTORY-RECOVERY-001/SPEC-HISTORY-RECOVERY-001-20261003T204023Z-65c21df0/`。

- red-recovery.log：真实应用关闭/重启两个用例，2 项均断言失败，0 errors/skip，终态均为 FAILED/HISTORY_COMMIT_FAILED。一个只提交模型响应；另一个先提交一个工具结果。模拟模型每次规划返回不同内容和工具 ID。
- green-recovery.log：首轮修复 24 项通过（QueryLoop 20、原 RunFlow 2、恢复 2）。
- green-replay-contract.log：26 项通过，含类型/前缀/摘要、旧历史拒绝与导航 UNVERIFIED 保护。
- green-pg-recovery.log：33 项通过（QueryLoop 20、HistoryReplay 5、关闭 6、PG 实际关闭恢复 2），均未跳过。PG fixture 用独立数据库，未复用线上数据。
- green-upgrade.log 与最终 migration 门禁验证 V22→V23 历史 messages/digest/operation/revision 均不改写，新增恢复字段为 NULL。

新增字段与 canonical 消息在同一 INSERT 事务；重放已提交工具恢复原 Evidence/Gap、plan/attempt 和计数，不再次执行。局部 replan 的测试在第二个工具提交后、轮末 checkpoint 前挂起，恢复后模型只调用最终回答、两个工具均未重执行。

最终命令和门禁退出码以本目录 command.log/verification.json 为准。尚未做全仓/浏览器回归或线上部署。对旧工具记录、未提交 READ、部分流式输出、总预算和历史投影的限制见 SPEC_HISTORY_RECOVERY.md；这里不宣称外部副作用恰好一次。

最终代码：73a4f20（数据/持久接口）与 927e5df（QueryLoop 接入、测试、契约）。原子命令 35 项通过，harness（含 5 项 Python）通过，migration 矩阵 27 项通过且 0 skip，包含 PG 的恢复 2 项+V22 升级 1 项。迁移最后新增的恢复字段成对约束也在 H2/PG 升级用例里断言通过；退出码均为 0。未执行项不计入这些数字。

后续只读复核发现跨 JVM 的恢复 JSON 对象键顺序可导致误冲突，已以确定性键序反例复现并修复，见 [SPEC_HISTORY_RECOVERY_SEMANTICS.md](SPEC_HISTORY_RECOVERY_SEMANTICS.md)。原有“恢复原 replan ID”只覆盖后续已提交工具携带新计划的路径，不涵盖最后工具触发的 replan；其余预算与投影边界已收窄契约并登记后续任务。
