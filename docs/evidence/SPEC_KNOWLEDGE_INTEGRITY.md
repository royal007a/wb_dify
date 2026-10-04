# SPEC-KNOWLEDGE-INTEGRITY-003：反例与验证

基线6e13407，计划60bfe30，红灯6f6e941/a9647fb。仅本地模拟JDBC、H2和隔离真实PG，不连接132、共享数据库、真实Provider或MCP。

## 红灯

- `narrow-red.log`：索引单测5项，3失败、0error/skip。元数据故障和未知产品仍被标为DONE；事务中DataSource实际借用2次而非1次。该模块失败后Maven未执行后续app测试，不把跳过模块当成集成通过。
- `narrow-red-workflow.log`：另跑H2/PG各18项，共36项、2失败、0error/skip。两个失败都为改DSL并重算checksum后，旧Agent会话仍COMPLETED，而不是FAILED。
- `narrow-red-metadata.log`：H2/PG各选1项，2失败、0error/skip。旧代码不经事务感知元数据回调，故障注入未命中，未进入预期FAILED。这是回调路径反例；真正元数据SQLException导致假成功已由第一条单测证明，不把未注入故障写成真实PG断网。

## 范围与当前状态

没有新增迁移或HTTP路由。新固定Workflow入口仍使用同一加载对象做摘要及DSL校验；取消/结算原用例仅变更spy/mock入口及预期checksum，不削弱其原断言。元数据错误处理只保证数据库仍可提交时落FAILED，不承诺数据库整体不可用时还能写库。

修复后窄测77项通过：索引5、engine14、Run控制12、知识复核H2/PG各19、结算H2/PG各4；失败/error/skip均0。完整backend/harness/migration结果待补。原始日志/XML不提交，只提交脱敏计数/SHA及可离线重算方法报告。未部署132。

## 首轮完整门禁与独立复核

1388db6的首轮门禁在2026-10-04 03:41:54Z结束，结果为failed，证据目录`harness/evidence/SPEC-KNOWLEDGE-INTEGRITY-003/SPEC-KNOWLEDGE-INTEGRITY-003-20261004T032219Z-4e2d89c3/`。backend记录595项、0失败、1错误、0skip/flaky；PostgresConcurrencyIntegrationTest在初始化阶段报ContainerLaunchException，只产生1条错误记录而没有执行其7项方法，不能称595项全通过。容器日志明确为初始化PG时`No space left on device`，随后等待ready日志超时。后续独立migration的118项全部执行、零失败/错误/skip/flaky；它不能抵消backend失败。Harness54项通过。原始verification及摘要保留，不覆盖、不改成通过。

mymacclaude对6e13407..1388db6静态复核，两条生产缺陷认可修复，未构建或跑测；提出合法Agent Chat正向计数不够精确的测试P2。下一次同任务重跑将补强恢复原DSL后合法执行的workflow_runs恰好+1、节点行+3、助手消息+1，再取得新鲜backend/harness/migration证据。当前不正式收口。

连接检查的范围仅是DocumentIndexingService；memory中的CanonicalMemoryIndexer和HistoryRecallService仍有直接借连接路径，它们元数据失败会抛错，不是本片修复的H2静默降级，不能写成全仓连接借用问题已消除。PG INSERT失败导致事务aborted后无法持久化FAILED仍在前述可提交边界之外。
