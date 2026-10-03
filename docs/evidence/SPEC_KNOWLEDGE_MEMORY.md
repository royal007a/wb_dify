# 知识门禁 memory 旁路与 canonical 回读关闭

任务 SPEC-KNOWLEDGE-FINISH-003，基线 21f04f0（前片最终证据是 1606300，不是源码 1c9ceab）。

目录：`harness/evidence/SPEC-KNOWLEDGE-FINISH-003/SPEC-KNOWLEDGE-FINISH-003-20261003T223822Z-bcc4281b/`。

## 红灯与修复

`red-memory.log`：43 项、4 失败、0 错误/跳过。三项 memory 测试直接复现目录泄漏和新索引；另一个反例是 canonical 回读直接抛 ExecutionSuspendedException 时，lifecycle spy 尚未变成 stopping，catch 仍吞掉显式挂起而开 Gap。这不是仅补绿灯测试，修复必须显式保留异常。

生产修改：MemoryDeliveryPolicy 按来源 Run 的不可变 AgentVersion / checkpoint 知识 Claim 决定 raw memory 是否开放；新索引跳过，旧目录/摘要、搜索、详情全部受保护。知识 Run 完成后也不开放整段 raw memory，合格最终回答仍走 Run/chat_messages。canonical history 一字不改，重放不走公开详情接口。

只读 Agent 查询端口在运行时取得（ObjectProvider），避免 AgentService → ToolCatalog → HistoryTool → MemoryPolicy 的初始化环；不启用 Spring 循环依赖容忍配置。来源版本/上下文不可读时 fail-closed。

## 验证过程

- 首次 green-memory.log 因新增依赖形成初始化环，54 项 context 错误；已修复依赖取得时机，保留失败日志。
- green-memory-2.log：54 项中 1 个夹具错误（同一 Run 重复插入 summaryVersion=1），并非通过。修正测试只创建一次旧摘要，不放松业务断言。
- green-memory-3.log：54 项通过、0 skip；KnowledgeMemoryBoundaryTest 21、KnowledgeAdmissionShutdownTest 22、HistoryReplayTest 9、ContextMemoryIntegrationTest 2。两组知识测试继承旧 18 项，不是全部新增场景。
- green-postgres.log：同套 memory 21、shutdown 22，共 43 项通过、0 skip。真实 pgvector PG16，测试自行创建隔离数据库容器，不碰共享库。

memory 测试通过 latch 卡住真实 canonical 校验时，确认 model:1 已持久化但 Run 仍 RUNNING；然后用 HTTP 检查目录、摘要、搜索及详情。人为插入旧索引/摘要，校验完成后仍不放出 raw memory；NEEDS_INPUT 和同会话另一调用方、没有版本的旧 checkpoint 同样拒绝。还检查新索引为空、canonical 行保留，恢复测试继续通过。

关闭测试覆盖入场与 canonical 回读的显式挂起 / stopping 加读异常四种组合，要求 RUNNING、run.interrupted，无永久失败/澄清/助手正文。它模拟生命周期信号，不宣称真正走了进程销毁路径；真实销毁顺序仍由原有 RunShutdownIntegrationTest / RunShutdownPostgresTest 证明。

## 未保证

未做浏览器/付费模型/部署，不新增迁移；不是语义真实性核验。已知 P2：基础设施故障被解释成澄清、部分检索事件口径、UI 核验范围提示、旧未固定 Agent 的恢复门禁，另行登记。PG 源码与最终命令/head 以本目录 verification.json 为准，最终门禁后补结论。

## 最终门禁（2026-10-04 06:49 CST）

源码 57b4ec5，测试/契约 8b6d503。verification.json 的 headCommit 为 8b6d503：定向 command.log 78 项、runtime 34 项、migration PG 矩阵 114 项，全部失败/错误/跳过为 0；harness 所有步骤退出 0。PG 矩阵包含两组新加入的 memory 21 / shutdown 22 项，不能把 H2/PG 或继承重复项计作独立新增场景。最终状态 completed 只代表本原子任务验收，不代表全仓库和部署完成；独立复核待回。
