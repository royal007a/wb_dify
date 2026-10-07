# Canonical history 恢复协议

问题：模型响应 model:N 先提交，checkpoint 在整轮工具完成后提交。关闭使 checkpoint 落后时，不能重新生成 model:N，否则非确定性模型会触发正确的语义冲突检查。

恢复过程：

1. 在下一次模型调用前按 runId + operationId 读取已提交记录。校验原始 messages_json 的摘要、当前完整前缀及恰好一条追加，再复用 assistant 响应。不存在时才调用模型。
2. 已复用响应不再发 model.started/model.completed/message.delta，也不重复计一次模型调用。原来的 history.committed 如尚未投影可按既有协议修复；不承诺所有审计投影恰好一次。
3. 每个新工具最终结果与恢复状态同一行、同一事务提交。V23 新增 recovery_json/recovery_digest，不改旧 messages_json/digest。恢复摘要绑定 canonical 摘要与恢复 JSON。
4. 恢复状态包含原结果分类、attempt、plan、Evidence/Gap 状态及该记录提交时的工具/召回次数和召回 token/耗时。复用时不执行工具，不重新制造 claim 或把导航文本升级为 VERIFIED；再运行后续决策。只有后续工具已经提交了新计划时，才从该工具的恢复记录还原 replan 的原 plan/step ID；最后一个工具触发 replan 的身份稳定性仍待补齐。
5. 同 operationId 不同消息摘要或语义不同的恢复元数据仍冲突；损坏摘要、前缀不符、非法结构均 fail-closed，父 Run 为 HISTORY_COMMIT_FAILED。恢复 JSON 的对象键顺序不具有语义，按解析后的 JSON 树比较；嵌套对象同样处理，但值、类型和数组顺序不能变化。先以原文检查 recovery_digest，再进行语义比较，不重写存量 recovery_json/digest，也不全局改变序列化器。不能覆盖历史来“修复”冲突。

边界：

- 旧 model:N 无需补数据即可复用。旧 tool:* 没有类型/状态恢复信息时拒绝自动重执行（HISTORY_COMMIT_FAILED，明确 Legacy 原因），不假装恢复成功。升级前应核对尚未结束的 Run；人工核对已发生操作后才决定停止旧 Run/新建工作。
- 已经发起但未提交 canonical 结果的 READ 工具可在恢复后再次执行；本片不保证此窗口外部副作用恰好一次。当前运行时仍拒绝非 READ，未扩大权限。
- 模型只流出了部分 delta、或 tool-bearing 响应在提交前关闭时，没有完整 canonical 响应可复用，仍可能再次调用和重复部分 delta。本片只消除已提交响应的重生成。
- 既有用户取消、能力快照和新工具执行资格检查保留。摘要校验是数据完整性校验，不是对抗拥有数据库写权限攻击者的签名。
- checkpoint 之前的未提交重试不在持久预算记录内。2026-10-07 修订：CHAT-RETRIEVAL-CONTROL-001 已让同一 Chat Run 按 createdAt 扣减总期限，并以 H2/PG 恢复反例及 05ab7a0 六范围验证；SPEC-RUN-BUDGET-001 仍保留原清单的独立验收工作，不再把“重启重置完整时限”列为当前源码行为。轮末 replan UUID、重复决策投影、replanDecisions/recallLatency 累计和失败统计边界仍属 SPEC-HISTORY-RECOVERY-003。不得宣传跨重启所有预算与审计投影已精确结算。
- 回滚可回退代码但保留 V23 列；旧程序产生的工具记录没有新恢复状态。不删除 canonical 历史，不回滚已发生外部操作。

验证：HistoryRecoveryIntegrationTest/HistoryRecoveryPostgresTest 用每次不同响应的模型和真实 context.close/restart；HistoryReplayTest 覆盖数据损坏、前缀/结构、元数据冲突、旧工具拒绝、导航证据与本地 replan。键顺序回归是确定性构造与本 JVM 相反的双键 input 序列并重算合法摘要，经过真实 QueryLoop 恢复，未声称该用例实际重启了 JVM。HistoryRecoveryMigrationTest 验证 V22→V23 旧历史不变。
