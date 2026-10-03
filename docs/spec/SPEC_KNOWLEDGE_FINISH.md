# 知识来源门禁与完成语义

范围：SPEC-KNOWLEDGE-FINISH-001。此门禁验证候选来源与引用完整性，**不是答案事实正确性/语义蕴含验证器**。不改 Workflow 编排、MCP 权限或已有工具循环。

## 路径与结果

| 情况 | 行为与可观察结果 |
|---|---|
| 普通 Agent 未绑定知识 | 原 QueryLoop 及流式 delta 不变，不要求知识引用 |
| 绑定知识，所有来源读取成功但零候选 | 模型调用次数为 0；Run FAILED / KNOWLEDGE_NO_EVIDENCE，无 assistant 消息；提示补问题或更新知识并重新发布、新建会话 |
| 任一绑定来源读取异常（即使其他库有候选） | 模型调用次数为 0；Run FAILED / KNOWLEDGE_RETRIEVAL_FAILED，无 assistant；事件只含稳定失败类别，不含原始异常消息，不回退模型常识 |
| 至少有候选，但模型没有 `[K数字]` 或包含未知编号 | HUMAN_INPUT_REQUIRED → NEEDS_INPUT；持久化 blocking Gap 与候选映射，未校验正文不作为 assistant 交付 |
| 最终引用的原文/摘要不可校验 | 同上，需要澄清或恢复来源，不冒充来源完整 |
| 至少一个合法引用，所有所引编号存在且 canonical 校验成功 | 仅 required 的“来源引用完整性”Claim VERIFIED；模型答案的 INFERENCE Claim 和 MODEL_OUTPUT evidence 仍 UNVERIFIED、非 required。其他 blocking Gap/工具完成标准仍由现有 FinishGate 检查 |
| Workflow KNOWLEDGE 零候选 | 节点 FAILED，不能继续 END；父 Run 走既有 WORKFLOW_ERROR 结算，不调用 QueryLoop |

正常完成事件加 `answerVerification=SOURCE_REFERENCES_ONLY` 与 `semanticClaimsVerified=false`；等待输入等未通过的知识路径为 `SOURCE_REFERENCES_NOT_VERIFIED`。普通聊天无这两个字段。Workflow 的 COMPLETED 仍只代表确定性图执行成功，不等于验证了文本事实；本片不伪造 Workflow 的 Claim 门禁。

## 来源与恢复

- 检索候选最初均为 UNVERIFIED。K 编号由服务端按本次候选顺序生成；Checkpoint 的 Evidence 保存 `knowledge:K编号:chunkId` 与 digest，system 上下文保存对应文本。
- CompletionVerifier 是 RunRuntimeIdentity 的应用层可插拔校验器，不让 Knowledge 检索逻辑进入 QueryLoop。现有 FinishGate 负责最终六出口判断。
- 恢复/用户澄清只读原 Checkpoint 的 K 映射，不重新检索、不按新的排序重新编号。旧 Checkpoint 缺少映射不能凭 `[K1]` 通过。用户把 Gap 标为已回答，也不能绕过下一次最终引用校验。
- 校验通过只标记本次实际引用的来源。canonical 读取通过公开 port，归档引用仍需要冻结成员与准确摘要。引用去重，不增加 LLM 调用。
- 为避免未通过校验的答案先出现在页面，知识绑定路径不发模型正文 `message.delta`，完成后前端按终态回读 Run；检索、模型、工具进度事件照常可见。普通聊天仍流式。这是有意的首字体验取舍，不宣称知识回答仍逐 token 展示。
- 失败追问保存带 Gap 的 checkpoint；正常完成不额外持久化“已推进下一 turn”的最终 checkpoint，以免提交前崩溃后重新生成下一轮。正常重启由已有 model:N canonical history 重放，再做来源校验。
- canonical 校验前检查取消/预算，返回后再次检查；尚未完成来源校验的关闭中断仍视为未完工作，不宣称 JDBC 阻塞可精确中断。

## 候选过滤及明确未保证

`hify.knowledge.min-vector-score` / `HIFY_KNOWLEDGE_MIN_VECTOR_SCORE` 默认为 0.5，合法范围 [-1,1]，NaN/Infinity 拒绝启动。它只过滤向量一路候选；H2 在内存余弦排序前过滤，PG 活跃/冻结检索都在 SQL 中过滤距离。关键词一路独立召回，随后仍按现有 RRF 融合；对外 score 仍是 RRF 分，不是相似度。

本仓库使用 64 维 hash bootstrap embedding，0.5 **没有黄金集准确率保证**，不叫 sufficiencyThreshold。词面匹配及高分向量仍可能无关；正交的“CEO是谁/退货政策”回归不证明能拒绝所有无答案题。重新索引、更换 embedding 或调整阈值可改变排序，语料冻结不固定检索算法。

`[K1]` 可被写在与原文矛盾的答案后面，本校验仍只确认来源存在。单测特意使用“原文七天、答案终身退货”并断言语义未验证，防止今后把完整性指标误报成事实准确率。语义蕴含、版本矛盾判断、关键事实覆盖、所有重要断言的 Claim 提取和线上准确率仍是未交付能力；需要独立评测与验证器，不以 prompt/引用计数替代。

## 验收

- KnowledgeCompletionVerifierTest：候选未验证、错误编号/混合编号、canonical 失败、旧无映射 checkpoint、用户解 Gap 不能绕过、恢复编号稳定、取消/期限、语义不保证的反例。
- KnowledgeFinishIntegrationTest / KnowledgeFinishPostgresTest：空库与无关候选在模型前拒绝、部分失败不回退/不泄露异常、缺引用 NEEDS_INPUT 的真实持久化/无正文 delta、普通聊天兼容、终态来源标记、Workflow 空结果不达 END、归档并新增资料后澄清仍读旧引用。
- 真实 PG 测试继承 H2 测试，不合计为互不重复场景；migration scope 强制相应报告 18 项、0 skip。

无新增 migration/HTTP 路由、无新外部模型、无实际付费调用。回退代码会移除本门禁，不删除历史快照/事件。验证记录见 `docs/evidence/SPEC_KNOWLEDGE_FINISH.md`，完整回归与部署状态只看任务清单及独立证据。
