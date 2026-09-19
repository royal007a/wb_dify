# ADR-0010：上下文预算、工具结果归档与 checkpoint 压缩

- 状态：Accepted
- 日期：2026-09-19

## 背景

只用消息字符数或单一 token 上限无法表达模型输出、恢复余量和安全余量。直接摘要完整历史还会先丢掉体积最大且可恢复的 Tool Result，并可能在“压缩成功”后仍超出窗口。更危险的是只衡量 token 降幅，忽略关键约束丢失、重复调查和无法恢复。

## 决策

1. 每次模型调用前计算 `input = window - output - reserve - safety`，工具 schema 与消息共同计入输入。
2. 超限后先把大 Tool Result 替换为 `history://tool-result/{toolCallId}`；原文仍留在 canonical Run history，模型视图不是新真源。
3. 归档后重新测量；仍超限才生成确定性 checkpoint，保留显式约束与最新交互闭包，再次重新测量。仍超限则以 `TOKEN_BUDGET_EXCEEDED` 终止，不截断后继续猜。
4. 评测至少记录关键约束保持率、重复调查率、恢复成功率和 token 降幅；token 降幅不能单独作为上线标准。

## 后果

- canonical history 保持完整，压缩只改变单次模型输入投影。
- 当前 token estimator 是可复现的保守估算，不等同于各 Provider 的官方 tokenizer；后续可替换实现但不得改变预算字段语义。
- 当前 checkpoint compactor 是确定性兜底，不做模型摘要。需要模型压缩时必须保持相同的重测与质量门禁。
