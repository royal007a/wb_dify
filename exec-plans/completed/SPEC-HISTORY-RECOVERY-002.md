# 跨 JVM 恢复 JSON 的语义幂等

基线 2aed65c。只处理 reviewer 提出的键顺序误冲突；剩余计划/预算/投影意见登记在 SPEC-HISTORY-RECOVERY-003，总期限仍是 SPEC-RUN-BUDGET-001。

1. 在真实本地 replan → 工具 B 提交 → checkpoint 前中断用例里，将持久 recovery_json 中双键 input 顺序改成与本 JVM 序列化相反，重新计算合法原文摘要。这是模拟另一个 JVM 写入，不是略过完整性检查。旧实现必须失败。
2. 保留原始 recovery_digest 的完整性校验。幂等比较用解析后的 JSON 树：对象键顺序不重要，数组顺序、类型和所有字段仍重要。不要全局改变 ObjectMapper，也不回填旧记录。
3. 单独覆盖实际值变化、数组重排、摘要损坏、空恢复字段，以及等价重放后原始 JSON/digest/revision 不变。
4. 运行恢复/关闭集成用例与 Harness runtime 门禁，提交代码和证据后请 mymacclaude 只读复核。此切片不部署、不访问共享服务或真实凭据。

回滚：代码可回退，不删除或重新签写 canonical 记录；没有新迁移。
