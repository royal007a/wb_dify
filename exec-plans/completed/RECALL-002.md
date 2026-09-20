# RECALL-002 — 召回评测与证据驱动演进

## Outcome

召回质量由版本化样本和可失败门禁决定；语义通道只在词法缺口被量化后加入。

## Scope / Non-goals

- In：八项指标、逐样本 artifact、阈值、词法基线、可选 pgvector/RRF。
- Out：Graph/reranker，除非当前样本证明多跳是主要错误来源。

## Steps and checkpoints

1. 冻结小型中文 holdout 和指标契约。
2. 跑词法基线并记录失败类型。
3. 按证据决定是否实现语义通道/RRF。
4. 运行 backend/migration/runtime/eval 门禁。

## Acceptance and evidence

见 `harness/tasks.json`；证据由 Harness 记录。

## Rollback / recovery boundary

所有索引均可重建；质量阈值变化必须留版本记录。
