# RECALL-001 — 分层上下文与迭代式召回

## Outcome

模型保留最近 K 轮原文，通过 summary/catalog 导航旧历史，并用结构化工具按需召回有来源详情。

## Scope / Non-goals

- In：recent-K、PostgreSQL 词法/时间/类型/实体过滤、search/detail 工具、Evidence/预算/no-progress。
- Out：ES、Graph、无限自动检索。

## Steps and checkpoints

1. 实现 search/detail service 与检索测试。
2. 扩展 ToolRuntime 注册端口，接入两个只读工具。
3. ContextManager 注入 summary/catalog，保留最近 K 轮。
4. 纵向测试 search → detail → Evidence → FinishGate。

## Acceptance and evidence

见 `harness/tasks.json`；证据由 Harness 记录。

## Rollback / recovery boundary

工具只读；移除派生索引不影响 canonical history。
