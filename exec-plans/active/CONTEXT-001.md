# CONTEXT-001 — 上下文预算归档与压缩评测

## Outcome

ContextManager 在模型调用前计算预算，优先归档 Tool Result，再用可验证 checkpoint 压缩，并输出质量指标。

## Scope / Non-goals

- In：预算公式、归档引用、压缩 port、重新测量、评测。
- Out：向量检索、MCP 延迟披露、跨 Run 长期记忆。

## Steps and checkpoints

1. 预算和消息闭包契约测试。
2. Tool Result Archiver 与 checkpoint compactor。
3. 接入 QueryLoop 前置 ContextManager。
4. 质量评测和 scoped verification。

## Acceptance and evidence

- Verify scopes：backend,runtime,eval。
- Evidence：由 Harness 生命周期记录。

## Rollback / recovery boundary

压缩只改变 Run 上下文表示；原始归档内容保留且可恢复。
