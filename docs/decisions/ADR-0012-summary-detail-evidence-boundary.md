# ADR-0012：摘要、细节目录与证据边界

- 状态：Accepted
- 日期：2026-09-20

## 背景

ADR-0010 的 checkpoint 只是在预算不足时生成确定性压缩文本；它没有统一描述普通对话、模型长输出、计划与工具结果，也不能回答“结论来自哪段原文”。如果把摘要或派生检索索引当成事实源，摘要幻觉、索引延迟和重建漂移会直接污染最终回答。

## 决策

1. `run_history_commits` 中的 canonical history 是唯一事实源。`history_detail_refs`、摘要和后续搜索索引都是可重建的派生投影，不得覆盖 canonical history。
2. 每条被压缩内容生成统一 `DetailRef`，记录消息类型、时间、实体/关键词、token 估算、source revision、message index 与内容 digest；读取原文必须回到指定 canonical revision 并校验 digest。
3. `ContextSummary` 是模型可读的导航和压缩表示，区分 conversation/section/model output/tool result/checkpoint；内容只组织目标、事实、约束、决策、状态和 open gaps。
4. 摘要中的每个关键 Claim 必须绑定 `sourceRefs`。缺少来源、来源失效或原文 digest 不一致时标为无效；同一 claim key 出现不同陈述时标为冲突，不能静默用新摘要覆盖旧事实。
5. 摘要本身不是 Evidence。只有通过 `DetailRef` 读取并校验过的 canonical 原文才能形成 VERIFIED evidence 并满足 FinishGate。
6. canonical 提交成功后同步维护 Detail Catalog；派生索引失败不回滚已提交历史，重复提交/恢复过程负责幂等补建。

## 后果

- 可以重建或替换关键词、向量和图索引，而不改变历史语义。
- 摘要的质量可按来源完整性、忠实度和冲突处理独立评测。
- 模型需要更多细节时必须显式 search/detail 召回，受次数、token、延迟和 no-progress 预算约束。
- 当前摘要生成器先采用确定性结构化提取建立契约；以后接入模型摘要时仍必须满足相同 sourceRefs 与验证门禁。
