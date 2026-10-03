# Workflow Run 取消与截止时间

任务SPEC-WORKFLOW-CONTROL-001，基线3672150。复用现有ExecutionControl，不另建Agent循环，不改知识快照语义。

## 反例与修复

`RunWorkflowControlTest`从公开的启动恢复/dispatch路径执行：Workflow取消被当MODEL_ERROR、超时被当MODEL_ERROR、持久取消后仍提交COMPLETED、恢复时重置全局预算。旧实现4/4失败（0错误/跳过），`/tmp/hify-workflow-control-red.log`；第一轮修复4/4通过，`/tmp/hify-workflow-control-green1.log`。

节点Worker测试新增8项：预取消不提交、排队取消不晚执行、排队消耗真实时钟预算、阻塞取消收到interrupt、同一截止时间过期、饱和拒绝不在caller执行、END之后取消仍不能成功、有界池配置。一次测试装配错误（覆写Mockito answer时when触发旧answer并传入null），日志 `/tmp/hify-workflow-control-green2.log`，不能记通过；改为doAnswer后重跑。Run层另补“终态锁之前刚提交取消”的确定性测试。

## 验证边界

- Workflow阶段不持有横跨IO的事务；每个节点状态单独提交，保留成功/失败/取消轨迹。无通用回滚。
- V21只扩大两张执行记录表的状态CHECK约束，旧DSL/checksum/记录不改。H2与真实PostgreSQL升级契约都先迁移到V20、插入旧历史、验证旧约束拒绝CANCELLED，再升级并验证历史保留、新状态合法、UNKNOWN依然拒绝。
- HTTP测试从新建知识库/Workflow/发布Agent/会话/Run/取消API出发，底层检索是受控阻塞spy；断言worker在finally清理前被中断、数据库取消标记、Workflow CANCELLED、无END节点和助手消息、无成功事件。
- Run终态/事件投影目前是两个动作，HTTP测试明确等候取消事件最终出现，不把它冒充A07事务窗口已关闭。
- 模拟worker接受interrupt，不证明实际PostgreSQL JDBC长查询同样立即停止；该驱动边界需要独立阻塞SQL测试。知识语料冻结A03、Knowledge FinishGate A01、SSE提交A07仍未完成。

分层代码提交：`0ed58fe`（V21/状态与升级测试）、`40f90d2`（节点执行控制）、`3d867e7`（Chat终态控制与HTTP测试）。模块重跑 `/tmp/hify-workflow-control-green3.log`：Workflow 33项、Run控制5项通过，0错误/跳过；这不是HTTP或PostgreSQL结论。

## 第一轮门禁：保留失败

`SPEC-WORKFLOW-CONTROL-001-20261003T162913Z-631e20c2`：模块33+5项及HTTP/H2 7项通过。普通Maven阶段的PG升级测试跳过1项，不能作为PG证据。

随后Harness配置真实Docker的migration scope：PostgresConcurrencyIntegrationTest 3项、McpCredentialPostgresTest 4项、H2迁移2项通过；WorkflowTerminalPostgresTest在initdb阶段因`No space left on device`报1个环境错误，未进入迁移断言。因此整次门禁失败，机器状态blocked。证据完整保留在该run目录，不能写全绿。

只读诊断：Colima `/var/lib/docker` 40G、99%使用、约430M可用；宿主机仍有57G。没有prune、删其他镜像/卷或重启Docker。后两组PG容器随后能正常启动，先按完全相同代码重跑；不先改变存储介质或放宽测试。

首次独立HTTP取消与H2升级运行也2/2通过，日志`/tmp/hify-workflow-control-http.log`。Spring启动270.7秒属于当时宿主机资源压力，不是线上HTTP性能指标。

最终验收待重跑，更未部署。
