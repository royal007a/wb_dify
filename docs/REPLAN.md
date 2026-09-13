# Replan 运行契约

## 1. 当前边界

本机制只覆盖 read-only Query Loop 的确定性修复，不接模型自动生成计划，不执行写工具确认或通用数据库回滚。它把原先“tool error 回灌后模型可能自行修复”的隐式行为，升级为可观察、可限额、可从 checkpoint 恢复的控制流。

Replan 属于 `hify-chat` 的 Run 编排层。`QueryLoop` 执行单次模型/工具循环，`ToolRuntime` 只负责工具注册、校验、权限与执行；工具不得自行修改计划。

## 2. 执行状态契约

| 契约 | 职责 |
|---|---|
| `ExecutionPlan` | 不可变计划版本、目标、步骤、父计划和摘要 |
| `PlanStep` | 动作、输入、前置条件、预期结果、风险、幂等与可逆属性 |
| `StepAttempt` | 一次工具尝试、tool call 配对、结果和失败分类 |
| `ExecutionCheckpoint` | 已配对消息、turn/tool 计数、plan id/version 和可恢复标志 |
| `ReplanDecision` | action 以及失败点、根因点、回滚点、Replan 起点和证据 |
| `ContinuationDecision` | 唯一六出口：`CONTINUE/FINISH/CLARIFY/RETRY/REPLAN/INTERRUPT` |
| `EvidenceItem` / `ClaimState` | 原子证据及其支持的事实/推断；完成所需 Claim 必须有 VERIFIED evidence |
| `GapState` | 缺输入、无能力、缺权限、瞬时失败和 no-progress 等结构化缺口 |
| `SuccessCriterion` / `FinishGate` | 用代码检查最终回答、阻塞 Gap 和 Claim 证据覆盖 |

四个位置必须分开：

- `failurePointId`：错误暴露的 Attempt。
- `rootCausePointId`：引入错误工具或参数的 Step；当前由确定性策略定位，后续可以引用更早的假设节点。
- `rollbackPointId`：最后一个可恢复 Checkpoint，不代表外部副作用已撤销。
- `replanFromStepId`：新计划从哪里重算；可以与 rollback point 不同。

## 3. 状态机

```text
PLANNED -> TRYING -> CHECKPOINTED -> TRYING ... -> COMPLETED

TRYING -> DIAGNOSING -> REPLANNING -> PLANNED (new version)
TRYING -> AWAITING_CONFIRMATION -> EXECUTING -> CHECKPOINTED
active state -> CANCELLED | FAILED
```

确认分支是已测试契约。当前 read-only 工具在 Try 中即可执行；没有真实 write 工具，因此尚无确认/执行 API。

## 4. 确定性策略

| 失败分类 | 决策 |
|---|---|
| `INVALID_ARGUMENTS` | 在 `maxReplans` 内 `LOCAL_REPLAN`；耗尽后 `ASK_HUMAN` |
| `TOOL_UNAVAILABLE` 且有启用的只读别名 | `LOCAL_REPLAN` 并记录替代工具证据 |
| `TOOL_UNAVAILABLE` 且无安全替代 | `ASK_HUMAN`，Run 进入 `NEEDS_INPUT` |
| `PERMISSION_DENIED` | `STOP`；不允许用 Replan 绕过策略 |
| `CANCELLED` | `STOP` |
| fatal/未知执行错误 | `STOP` |

默认 `maxReplans=2`。max turns、tool calls、token 和 wall-clock deadline 仍是全局预算；Replan 不重置预算。模型返回的每个 tool call 必须有配对 tool result，即使前序调用已触发人工门禁，后续调用也写入明确的 skipped result。

瞬时工具失败不进入 Replan，先产生 `RETRY`，并受 `maxRetries` 和全局 tool-call budget 双重限制；重试耗尽产生 `INTERRUPT/RETRY_EXHAUSTED`。对同一语义状态连续两次没有新增的 VERIFIED evidence 或 Gap 变化，产生 `NO_PROGRESS` Gap 并 `CLARIFY`，禁止机械循环。

## 5. 六出口与完成门

| 出口 | 含义 | 当前触发点 |
|---|---|---|
| `CONTINUE` | 本轮有新增可信结果，继续模型/工具循环 | 工具成功 |
| `FINISH` | 所有可执行成功标准通过 | 非空最终回答、无阻塞 Gap、required Claim 有 VERIFIED evidence |
| `CLARIFY` | 需要结构化用户输入后再恢复 | 缺参数、无安全替代、no-progress、FinishGate 缺口 |
| `RETRY` | 原步骤有限重试 | 瞬时工具错误且仍有 retry/tool budget |
| `REPLAN` | 从记录的决策点生成新计划版本 | 参数错误或安全只读替代 |
| `INTERRUPT` | 不应继续自动执行 | 权限拒绝、取消、fatal error、重试耗尽 |

`ContinuationDecision` 保存 reason、planVersion、attemptId 及当时的 evidence/gap 引用。模型可以提出工具调用，但不能自行宣布完成、绕过权限或扩张预算。

## 6. Try / Confirm / Cancel

- **Try**：read 工具可以真实执行；未来 write/execute/external 工具只能做 preflight 或受工具支持的 dry-run。
- **Confirm**：必须绑定精确的 `planId + planVersion + planDigest + stepId + canonical input + resource version + expiry`。计划或参数变化后旧确认失效。
- **Cancel**：`cancel_requested_at` 先持久化，再触发进程内 token；token 传播到模型 HTTP 等阻塞调用。提交外部副作用后，Cancel 只停止后续步骤，不能伪装成 rollback。

## 7. Checkpoint、Gap 恢复与版本

`run_checkpoints` 保存完整、已配对的 RuntimeMessage 列表、预算计数、Plan，以及 Evidence/Claim/Gap 快照；`evidenceVersion` 和 `gapVersion` 可证明恢复使用了哪个逻辑状态。初始输入与每个工具轮次后各写一个 checkpoint。应用启动时：

1. 对已请求取消的 `RUNNING` Run 收敛为 `CANCELLED`；
2. 其余 `RUNNING` Run 从最新 restorable checkpoint 恢复；
3. 如果没有 checkpoint，则仅允许按原始会话重启当前 read-only Run。

`NEEDS_INPUT` 是当前 Run 的终态。客户端从 `continuation.decided` 取得 `gapIds`，下一条消息创建子 Run，并传 `resume.runId + resume.gapIds`；服务端只允许恢复同一 Conversation 中、状态为 `NEEDS_INPUT` 且有可恢复 checkpoint 的 Run。子 Run 继承预算计数和 Plan，关闭指定 Gap，加入一条可追溯的 USER_INPUT evidence，并记录 `resumedFromRunId/resolvedGapIds`。

当前 checkpoint 适用于 read-only 工具。引入写工具前必须增加 side-effect ledger、工具幂等键和 checkpoint 可恢复性判定；不可逆副作用不允许自动回滚或重复执行。

## 8. 事件

新增：`plan.created`、`step.try.started`、`step.try.completed`、`step.try.failed`、`continuation.decided`、`context.state.updated`、`replan.decided`、`recovery.narrated`、`confirmation.required`、`confirmation.accepted`、`confirmation.cancelled`、`checkpoint.created`、`checkpoint.restored`、`run.input.accepted`、`run.needs_input`。

`context.state.updated` 只投影版本、数量和开放 Gap，不输出原始工具结果；`recovery.narrated` 同时记录失败点、根因点、回滚点、Replan 起点与六出口，让“哪里报错”和“为什么重算”可分开审计。

`confirmation.accepted/cancelled` 目前是保留契约，没有写工具前不会由主链路产生。

## 9. 当前明确不做

- 不做 Workflow 级分支、统一候选排序、双层 TAO 或子 Agent。
- 不让模型自动选择回滚范围，不做阶段/全局回滚。
- 不实现 write 工具确认、side-effect ledger、compensation 或通用数据库回滚。

## 10. 下一阶段前置条件

真实 write 工具进入前必须先完成：精确确认 token、side-effect ledger、幂等执行、补偿动作显式注册、风险升级重新确认，以及“已提交副作用绝不自动重放”的故障注入测试。
