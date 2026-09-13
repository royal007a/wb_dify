# Hify 意图路由纵向切片

## 1. 目标与当前边界

本切片先建立可评测、可解释、可安全降级的输入理解契约，不以“接入更多模型”作为完成标准。当前只支持 `unknown`、`clarify`、`tool`、`workflow` 四类出口，并通过独立预览接口验证；它尚未接管 Run/Query Loop 主链路，也不会静默改变现有对话行为。

输入理解属于 Query Loop 之前的路由层：它判断候选意图和缺失槽位，不执行工具、不启动 Workflow、不修改 Run 终态。真正的 schema、权限、预算和副作用控制仍由 ToolRuntime/WorkflowRuntime/QueryLoop 强制执行，意图判断不能绕过这些边界。

## 2. `IntentDecision` 契约

```json
{
  "intent": "calculate",
  "confidence": 1.0,
  "normalizedInput": "请计算 12.5 * 4",
  "slots": {
    "toolName": "calculator",
    "expression": "12.5*4"
  },
  "missingSlots": [],
  "route": "tool",
  "evidence": [
    {"source": "RULE", "reference": "tool.calculator_expression", "detail": "请计算 12.5 * 4"}
  ],
  "reason": "deterministic_rule_match"
}
```

代码硬约束：

- `confidence` 必须为 `[0, 1]` 内的有限数；输入、意图、路由和原因不能为空。
- `unknown` 路由只能使用 `unknown` 意图。
- `tool`/`workflow` 是可执行出口，不能携带 `missingSlots`。
- `tool` 必须有非空 `slots.toolName`；`workflow` 必须有非空 `slots.workflowId`。
- 模型返回不合法 JSON、未知路由或非法候选时 fail closed，不猜测执行目标。

模型软判断：同义表达、业务语义、候选意图排序和置信度。模型只生成候选；是否追问以及能否进入可执行出口由代码决定。

## 3. 两层路由

```text
normalized input
  -> deterministic rules
       cancel/help/exact command/dangerous action/required slot
  -> structured model classifier (only when no rule matched)
       JSON candidates, max 3, current Agent model, temperature=0
  -> policy gate
       confidence < 0.75 -> clarify
       top1 - top2 < 0.10 -> clarify
       missing required slot -> clarify
       invalid candidate -> clarify/unknown
  -> IntentDecision
```

确定性层先处理取消、帮助、高风险动作、精确 Workflow 命令、时间与计算器规则。高风险动作只产生 `clarify`，不直接执行。结构化模型层复用 Agent 当前 Provider/Model，不预设“轻量模型 → 深度模型”；规则命中时使用 lazy model factory，避免无意义地初始化 Provider 或产生模型成本。

当前结构化输出基于 JSON 内容契约，因为现有 OpenAI-compatible `ModelClient` 尚未暴露 provider-native `response_format/json_schema`。进入真实 Provider 评测前，应扩展该 port 使用供应商原生结构化输出，并保留当前解析失败降级行为。

## 4. 预览 API

`POST /api/v1/intent-decisions`

```json
{
  "agentId": "demo-agent",
  "input": "运行工作流 daily-report"
}
```

接口用于标注校验、Console 调试和 shadow rollout，不会执行返回的 route。调用方不得把预览结果当作授权结果；以后接入主链路时必须再次经过目标 runtime 的权限、schema、预算和幂等检查。

## 5. 评测基线

版本化数据集位于 `backend/hify-chat/src/test/resources/intent/intent-eval.zh-CN.tsv`，当前 120 条人工标签覆盖：同义表达、精确命令、缺槽、多轮指代、边界重叠、越界请求、错别字、全角字符、危险动作和 prompt injection。

测试 `IntentEvaluationDatasetTest` 固定输出：Top1 accuracy、unknown precision/recall、澄清率、槽位准确率、route/intent 混淆矩阵、P95 延迟和单次成本。首份 rule-only 证据见 `docs/evidence/intent-routing-rule-baseline.json`。

首轮基线 Top1 为 `72.50%`，`unknown recall=100%` 但 `unknown precision=23.26%`。按 intent 混淆矩阵只补齐明确的帮助、取消、系统时间和危险动作同义词后，v2 Top1 达到 `82.50%`、槽位准确率达到 `80.91%`，且 unknown recall 仍为 100%；证据见 `docs/evidence/intent-routing-rules-v2.json`。

v2 剩余错误主要是多轮指代、错别字/中文数字和自然语言 Workflow 名称，继续堆规则的边际收益已经明显下降。下一步应该评测真实结构化模型并注入受控的会话/Workflow catalog 上下文，而不是铺向量召回或动态 few-shot。

## 6. 后续门槛

1. 先用当前配置模型跑离线/受控评测，记录模型 token、价格、P95 和结构化输出失败率。
2. 通过 shadow mode 写入 `intent.decided` 观测事件，但不改变 Run 路由；人工复核误路由和敏感样本。
3. 只有离线与 shadow 指标达到约定门槛，才让 `unknown/clarify` 影响交互，让 `tool/workflow` 进入各自 runtime。
4. 只有混淆矩阵证明边界混淆是主要错误来源，才增加 few-shot、词汇表、向量候选召回或轻/深模型分层。

建议上线门槛应在真实样本评测后冻结；在此之前不伪造目标数。任何优化都必须同时报告质量、延迟、成本与安全回归。
