# SPEC-RUN-BUDGET-001：与后续预算切片的关系

2026-10-07 补录。任务原始验收条件、pending 状态和空 evidence 列表保持不变；
以下是验收映射，不是另造一次 runner 执行，也不是重新实现已经修复的行为。

## 原始计划（历史起点，不代表当前仍未实现）

Review follow-up, not implemented: reproduce a Chat Run older than its budget recovering into a fresh QueryLoop timeout; compare Workflow behavior. Define one remaining-budget calculation and cancellation/deadline/shutdown precedence, then test before model/tool execution, queued dispatch and restart. Preserve frozen capability/history. No new deployment or distributed scheduling scope.

## 已被后续切片覆盖的部分

CHAT-RETRIEVAL-CONTROL-001 已通过完整六 scope 和独立只读复核。
固定受测源码为 05ab7a0，证据提交 d913f87，说明见
`docs/research/jikesummary-20261006/full-gate-05ab7a0.md`。

- RunApplicationService.runControl 从持久 createdAt 扣除已用时长；同一 Run
  的检索和 QueryLoop 共用父控制，boundedBy 不延长父预算。
- H2/PG 关停恢复类包含及时恢复和已到期恢复正反例；已到期时没有模型调用、
  assistant、message.delta 或 checkpoint.restored，旧 checkpoint 保留。
- 显式 resume API 创建新的 AgentRun，不属于上述同一 Run 自动重启；
  不声称一个用户意图跨多个新 Run 的累计总期限受该控制约束。

## 为什么原任务还未标为完成

原清单还要求“关闭、取消、期限同时出现的优先级有确定性交错测试”，以及
同一幂等 Run/checkpoint、时钟和排队边界的完整映射。目前不以其他切片整套
测试通过自动替代逐条审查；后续先核对已有测试与缺项，只补确有缺口的反例。
原 runTimeout 重置描述保留为历史起因，不再作为当前实现结论。

恢复起点的墙钟差与进程内单调计时不同；父预算最多为配置 runTimeout，
不承诺抵御任意跨进程墙钟跳变。Run 创建之后的排队/停机间隔会计入剩余预算，
入库之前的准入耗时不在其中；协作式检查不是硬返回期限。
本地验收不代表已经部署，线上身份仍以部署记录为准。
