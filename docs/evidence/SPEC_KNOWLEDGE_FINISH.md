# Knowledge 来源门禁验证

任务 SPEC-KNOWLEDGE-FINISH-001；基线 74390d6。只在本仓库、H2 与独立 Testcontainers PG 验证，不操作 hify-cc、真实业务数据、凭据或 132。

目录：`harness/evidence/SPEC-KNOWLEDGE-FINISH-001/SPEC-KNOWLEDGE-FINISH-001-20261003T215851Z-6a2cffa8/`。

## 失败证据与调整

- 原实现 red 日志：17 项中 6 failures、0 errors/skip；空库仍调模型、无关向量仍返回 topK、部分来源失败继续、缺引用/未知引用仍完成、无来源校验标记、Workflow 空结果走 END。普通聊天和继承的发布快照回归通过。
- first-green.log：前述路径大部分转绿，追问仍失败。clarify-diagnostic.log 定位到 `Illegal plan transition: PLANNED -> AWAITING_CONFIRMATION`；原状态机遗漏无工具首轮的澄清出口。允许该迁移，并用真实 Run/Checkpoint 断言补回归，没有吞异常伪造 NEEDS_INPUT。
- second-green.log 编译失败：新测试使用了不存在的 ClaimState.required()；改为实际 requiredForCompletion()，保留失败记录。
- third-green.log：定向 96 项通过（Workflow17、Chat33、应用46），0 skip。含知识门禁单位10、H2知识18（其中10项继承快照测试）、原QueryLoop、HistoryReplay、Agent与Knowledge API 回归。
- green-pg.log：真实 pgvector/pg16 18 项通过、0 skip；包含 SQL 候选阈值和归档来源恢复，使用假模型但真实数据库。没有实跑外部 LLM、浏览器或部署。

## 解释边界

契约见 `spec/SPEC_KNOWLEDGE_FINISH.md`。来源标为 VERIFIED 的只有 identity/digest，不是答案语义。刻意矛盾答案的单位测试仍能完成，但输出语义 Claim 为 UNVERIFIED，证明边界而不是证明生成准确。向量阈值不是充分性评测，关键词仍可能召回无关文本；不能把本任务写成“彻底消除幻觉/完整 grounding”。

Resume 用 checkpoint K 映射；归档旧文档并新增十五天政策后，对旧 Run 的澄清仍引用七天原文，Spy 明确断言没有再次 searchRevision。最终正文校验前不推 delta；引用失败无 assistant 消息，普通聊天不变。无新增 migration，仅在现有 runtime 增加应用校验端口。

上述为中间定向结果。最终 task command、harness/runtime/migration 门禁以同目录 command.log 和 verification.json 为准；独立 reviewer 结果另记，不将待复验写成通过。
