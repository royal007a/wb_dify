# 恢复元数据键顺序复验

任务 SPEC-HISTORY-RECOVERY-002。基线 176fb51（注册任务，业务代码同 2aed65c）。范围仅 ~/hify；没有修改 hify-cc、没有访问共享服务/密钥，没有部署，也没有新迁移。

证据目录：`harness/evidence/SPEC-HISTORY-RECOVERY-002/SPEC-HISTORY-RECOVERY-002-20261003T210830Z-27c07002/`。

## 反例与修复

HistoryReplayTest 在本地 replan 后的第二个工具已经提交、轮末 checkpoint 之前挂起。将持久恢复 JSON 中 input 的两个键改为本 JVM 序列化顺序的反向，重算匹配原始消息摘要的 recovery_digest，模拟另一 JVM 的合法写入。

- red-semantic-replay.log：9 项，1 failure、1 error、0 skip。顺序不同的等价恢复记录抛 HistoryOperationConflictException（review 原反例）；另一个失败是损坏摘要在直接 commitTool 时原先被归成普通冲突，没有检查完整性。
- green-semantic-replay.log：同 9 项全部通过。新增值变化、类型变化、数组重排依然冲突；摘要损坏在 replay 和直接 commitTool 两条入口均拒绝。原始的计数冲突、前缀与旧工具拒绝仍覆盖。
- 同一 JVM 与模拟另一 JVM 两种 replan 恢复均完成，原计划版本 2、累计工具次数 2；恢复不调用工具，模型总共只调用规划和最后回答各一次。原 recovery_json、recovery_digest、revision、semantic_digest、messages_json 全部保持不变。

实现选择：先验证存量恢复 JSON 的原文摘要，再用 JSON 树比较元数据语义；只忽略对象字段顺序，不忽略数组顺序或值/类型。不采用全局排序配置，避免改变已有 canonical 消息摘要；不覆盖旧行。

## 证据边界

这是确定性构造另一 JVM 键序的测试，不是实际 fork 多个 JVM 的概率性测试。生产关闭/恢复和运行时门禁结果见本目录最终 command.log / verification.json；仅这两份最终记录可用于声称整个原子任务通过。

review 的其他 P2 没有混进这次修复：最后一个工具触发 replan 的 UUID、重复决策投影、累计预算/失败统计登记到 SPEC-HISTORY-RECOVERY-003，Run 总期限仍归 SPEC-RUN-BUDGET-001。契约已收窄原有表述。不把静态意见当成新的实测结果，也不把这次通过当成整个恢复协议已无缺口。
