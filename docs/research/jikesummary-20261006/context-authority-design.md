# 下一候选：外部资料与策略权限分离（设计草案，未实现）

> 2026-10-07 五轮修订：以下「修订契约」替代后面的旧方案；旧方案保留为审查轨迹，
> 不再作为实现依据。特别撤回 canonical 新角色、新 DetailRefKind 以及恢复入口直接拒绝。
> 859b428 修订已独立只读评审通过，5030714 明确其 P3 边界；已登记
> CONTEXT-AUTHORITY-001，尚未改产品；旧 writer 金样已独立采集 1 项通过，
> 不是新防护测试。旧方案仍仅为审查轨迹。

## 修订契约：只改变模型视图，不改变 canonical 协议

### 1. 权限来源如何进入 prepare

- 新增仅存在于内存的来源描述（暂名 ContextAuthority），绑定 runId、固定 AgentVersion
  及 canonical 前缀身份，保存可信策略位置、资料位置和待核实状态。它不是模型可填写字段，
  不写入 RuntimeMessage、checkpoint 或历史 JSON，不增加 DetailRefKind 枚举。
- RunApplicationService.execute 在组装消息时建立该描述；经 RunRuntimeIdentity 显式传给
  QueryLoop，再作为 ContextManager.prepare 的输入。生产入口不能使用“信任所有 system”
  的缺省值；既有 local 测试入口要显式声明合成策略位置，不能给生产提供回退旁路。
- prepare 内部的投影值区分 POLICY / CONTEXT_DATA / 普通对话，只有最终模型视图把资料
  表示为内部 context_data；该视图不回写 canonical。分层摘要/目录必须在生成时携带来源，
  固定导航规则另用服务端常量，不能从摘要或正文提取策略。
- 原 canonical 仍是四字段、既有角色；旧引用、摘要、revision、replay prefix 不变。
  新 Run 的 RAG canonical 仍按旧兼容形态存 system，安全性来自每次调用的视图门禁，
  而不是存储标签。回退旧 jar 不会因新角色/枚举报错，但会恢复旧的资料提权缺陷，
  因此回退属于安全能力降级，不能宣称回退仍保有新防护。

### 2. 旧来源的核实与恢复时点

- 只接受旧生产前缀两种形态：首条 system 与固定版本 instructions 逐字一致；
  或在其后恰有一个旧知识 system。不能仅凭 KNOWLEDGE_CONTEXT 等正文头部授信。
- 知识资料用持久化 knowledge.retrieval.completed 的有序引用（chunkId、documentId、
  documentVersion、digest）和固定发布语料重建旧正文，核对每块身份/摘要及整段逐字一致。
  显式续接沿持久化 **resumedFromRunId** 找实际来源 Run 的事件，并检查会话与版本归属；
  链缺失、循环、确定不存在的引用、正文不一致或其他 system 排列属于确定性不一致。
  数据库暂时不可用不等于引用不存在，不能笼统把所有“无法回读”判为来源未核实。
  旧知识串里的固定英文规则不从原文“升级”为策略，新视图只用服务端固定规则。
- 恢复入口只构造待核实的描述，**不抛来源异常阻止 canonical replay**。
  QueryLoop 先用原字节前缀读取 model:N。没有已提交响应、确实要调用模型时，
  在 `onModelStarted` 之前执行来源门禁；未知来源以明确兼容失败终止，模型零调用。
- 有已提交响应时仍复用它，不重调模型、不删除/改写已提交历史。复用不等于允许继续执行：
  未执行的 tool call 在工具调度前还必须通过同一来源门禁；来源未核实则工具零调用，
  保留已提交响应并记兼容失败。纯 final 响应同样不绕过交付门禁，不因已提交就自动发布。
  这区分“保存并复用已提交事实”与“授权新副作用/交付成功”，不把前者冒充后者。
- 已核实的来源可继续工具/终态流程，后续真正的新模型调用使用降权视图。
  以下门禁/异常映射是实施前契约，不再留给实现时自行决定。

#### 2.1 固定门禁位置和已提交调用的处理

以 05ab7a0 的 QueryLoop 为定位基线（实施后行号会变化）：

1. 保留 133–142 行的 suspended、cancelled、expired 检查，再读取 `model:N` replay。
   没有 replay 时：来源核实 → 再检查父控制 → `onModelStarted`（原 152 行）→ prepare
   → generateStream。不能让来源拒绝产生一个“模型已经开始”的事件。
2. 有 replay 时先按原规则验证、复用已提交 assistant，不调模型、不改写历史事实。
   在原有响应后控制检查之后、CompletionVerifier / FinishGate 和整个 tool loop 之前，
   再走来源门禁。此门禁覆盖 `tool:<id>` replay，以及 CLARIFY / INTERRUPT 的 skip
   分支；它们不能因为“不是真正新调用”而绕过来源准入。新响应亦走这个出口门禁。
3. 来源不一致时立即 FAILED：不执行待执行工具，不追加合成 tool error，不伪造
   `tool:<id>` 已提交，也不抹掉此前真实提交的工具结果。历史中可保留尚未配对的
   assistant tool_calls，但该失败 Run 不再进入模型、工具循环或 NEEDS_INPUT。
   FAILED 不能显式 resume（现有入口只接受 NEEDS_INPUT），也不能被自动恢复挑回运行。
   新 Run 不能把失败 Run 的这段 canonical tool_calls 混进请求；须有回归反例。
4. 已提交响应仍可通过历史接口回读，故“无成功交付”只表示不进入 COMPLETED、
   不写成功 assistant 交付，并非删除历史或撤回已推送 delta。提交前已经发出的 delta
   无法收回；本切片不追溯保证它们从未泄露，也不把既往输出伪装成不存在。

#### 2.2 明确失败分类、文案和预算

| 情况 | 终态 / TerminalReason（拟新增的明确标出） | 用户提示与重试规则 |
| --- | --- | --- |
| 确定性来源不一致、缺失或歧义 | FAILED / **CONTEXT_AUTHORITY_MISMATCH（新增）** | “上下文来源无法核实，本次运行已停止；请提交一个新请求。”不自动重试、不续接该失败 Run；若它由显式续接创建，源 Run 仍可能是 NEEDS_INPUT，但再次续接相同来源会再次拒绝，不能提示用户反复点原续接。 |
| 来源存储读取失败，无法完成核实 | FAILED / **CONTEXT_SOURCE_UNAVAILABLE（新增）** | “上下文来源暂时不可用，本次运行已停止；请稍后重试。”不自动重试、不续接该失败 Run；可新开请求，或在源 Run 仍符合 NEEDS_INPUT 准入时重新显式续接，由新 Run 重新核实，不跳过门禁。 |
| 取消 / 到期 / suspended | 保持既有控制异常及处理路径 | 不转换成来源异常；取消与到期同时出现仍按取消，关停恢复语义不变。 |

两类新异常必须在 QueryLoop 的通用 RuntimeException→MODEL_ERROR 分支之前单独映射，
RunApplicationService.finishFailure 也做同样的兜底映射；客户端文案使用固定安全文本，
不拼接原始异常、资料、路径或连接信息。实现时同步更新终态展示/统计契约，不新增 RunState。

核实使用原 Run 的 ExecutionControl，不新开完整时限：每次事件/来源读取前后、每块
重建之后、最终判定前检查控制；catch 中先原样传播三类控制异常，再检查父控制，随后
在分类前检查 `shutdownInterruption(failure) || lifecycle.isStopping()`，成立则抛
ExecutionSuspendedException，最后才分类数据不一致与存储故障。数据库故障绝不转成
“无引用”，不依赖异常消息文本分类。
只读操作也计入剩余预算，迟到核实结果不能继续调用模型/工具；底层数据库读取的即时
中断能力不是本切片新增保证，仍是协作式截止，不能宣传严格墙钟硬上限。

关停检查由应用层提供的来源核实 port 封装（可经 ContextAuthority/RunRuntimeIdentity
传递），QueryLoop 不直接依赖应用生命周期实现。生产 control 通过
`withShutdown(lifecycle::isStopping)` 实时读取生命周期，isSuspended 并非异步送达的信号；
不存在“stopping 已置位而 control 尚未收到暂停”的独立状态（显式取消仍优先）。
来源读取及新调用前的检查用于尽早中止、阻止新的模型/工具调用；已有 execute catch
也会通过 checkActive 重查控制。它们不是各自独立保证最终持久状态的多层门禁。
真正需要补的是 QueryLoop 返回 Result 后的 finish 路径：当前不再检查控制，且来源判定
与最终提交之间仍可发生关停。RunApplicationService 对两类来源异常及返回 reason
都汇入同一个终态提交边界；在事务内锁住 Run 后、写入来源失败之前重查生命周期与
持久取消。仅提交边界本身不重新判断到期，不调用通用 checkActive；到达该边界的来源
失败不会在这里仅因迟到超时改名。异常路径仍保留 execute catch 的 checkActive：若在
异常抛出与 catch 之间到期，仍可转换为 TIMED_OUT，不承诺全链路 reason 不变。
CONTEXT_AUTHORITY_MISMATCH 与 CONTEXT_SOURCE_UNAVAILABLE
两类都适用关停兜底，MISMATCH 在恢复后重新核实；不把已经算好的正常成功统一改为暂停。
未终结且关停中的工作沿既有 APPLICATION_SHUTDOWN 路径写 run.interrupted，保留
RUNNING 供恢复；持久取消仍优先。门禁先算出拒绝但尚未提交的窗口不能视为已永久失败。
ExecutionLifecycle 在本上下文的 ContextClosedEvent 回调中以最高监听优先级单向置位
volatile stopping。这里依赖的是本应用生命周期信号，不把所有存储故障都归因于关停。
锁行后的检查已读到 stopping=true 才转换；检查通过之后才开始关停，来源 FAILED 仍可
提交，这是本设计选择的检查点语义，不宣称标志读取与事务提交具有额外全局原子性。
若事务因关停未能提交，不能宣称已持久化失败或 interruption，仍按实际落库状态恢复。

#### 2.3 多事件、checkpoint 和重建的唯一规则

- 不选“最新一条检索事件”：遍历当前 Run 及合法来源链上的候选事件，用各自有序引用
  重建，只有身份/版本/digest 全部有效且正文与 checkpoint 对应资料逐字相同才候选通过。
  若多条事件的有序身份、digest、重建正文完全相同，视为同一来源，按来源链最近 Run、
  该 Run 内最小持久事件序号选取，仅用于确定性诊断；不同来源映射出现歧义则拒绝。
  零匹配且读取完整成功才属于 MISMATCH；核实所需读取有故障时属于 UNAVAILABLE，
  不能把被吞掉的异常计成一个不匹配。不得用重新检索/重新排名替代已固定的 K 编号。
- completed 必须属于可辨识的成功检索段：同一 Run 按持久序号找到其前一条尚未配对的
  started，区间内有 failed 就排除该 completed，不能用部分成功的引用授予完整来源。
  缺 started、同一段多条 completed 或其他无法唯一配对的形态按不一致处理；遇到新
  started 时先前未完成的段不与新 completed 拼接。当前代码会先发布 failed 再发布
  completed（RunApplicationService:919–927），因此有 completed 本身不是成功证明。
  citationCount=0 的 completed 也排除：现有路径随后抛 KNOWLEDGE_NO_EVIDENCE；要求
  citationCount 为正且与引用条数相符，不把空引用的完成事件当作成功来源段。
- canonical/checkpoint 的已提交字节是要保持的历史事实；通过逐字匹配的事件提供来源
  证明；contextState 的 `knowledge:Kn:chunkId` 加 valueDigest 不是另一套可覆盖事实。
  对知识候选要求它与证明中的 K→chunkId/digest 一一对应，缺失、重复或冲突均拒绝，
  不通过修改 state 或重排 K“修好”旧记录。后续资料正确也不提升模型断言为语义 VERIFIED。
- 来源描述不落盘，**包括新 Run 的崩溃恢复**都要走上述重建，不能只在旧版本数据上核实。
  原地重启与显式续接分别覆盖；来源链会话/版本不符时不越界搜索其他 Run。
- 固定语料重新发布不授权换成新 chunk。若旧 chunk 仍可按原版本和 digest 回读，可核实；
  若已被清理或不可再回读，合法旧 Run 也会确定性降级为 MISMATCH，需新开 Run。
  这是可用性取舍，不自动把“曾经可信”当成现在可验证，也不批量改写历史。

#### 2.4 核实结果的生命周期与失败提交窗口

- 每次 `RunApplicationService.execute` 调用建立独立的内存核实作用域，不跨执行、
  恢复、显式续接或不同 Run 共享，也不以全局 runId 缓存替代来源重建。
- 首次实际门禁必须完整核实，包括新 Run：手中的本次检索候选只是重建输入，不能
  因“刚检索过”跳过事件配对、固定版本、引用摘要、正文与 contextState 对照。
  新 Run 没有 checkpoint 时以其即将进入模型的 canonical 前缀作为对应历史事实。
- 只缓存已通过的来源结论，绑定固定 AgentVersion 身份及其摘要、策略/资料位置、
  被核实 canonical 前缀字节、K 编号映射与来源证明身份。普通后续 assistant/tool
  追加不改变已核实的前缀；若这些绑定值改变，拒绝复用，不靠位置碰巧相同命中。
  局部成功、读取失败或歧义不缓存。逐轮仍检查控制、执行租约和出口准入，缓存不是
  继续运行权限，也不能替代既有 KnowledgeCompletionVerifier 的语义/来源检查。
- 同一执行作用域中通过后不因每轮重新读取同一来源而随机翻转为 UNAVAILABLE；恢复
  后必须重新读，所以旧 chunk 随后清理可能导致下一次恢复拒绝。这是明确的快照取舍，
  不是永久保鲜保证；使用中的不可变内存证明不得被后续检索覆盖。
- “不自动重试”仅指来源失败终态成功提交之后。若拒绝已经算出但事务提交前崩溃，
  Run 仍是 RUNNING，既有恢复会重新执行核实；不伪造已提交终态阻止这类恢复。

### 3. 协议与投影不变式

- OpenAI（含 OPENAI_COMPATIBLE 共用客户端）、Anthropic、Gemini 的同步/流式入口
  都使用显式角色白名单。内部 context_data 映射到普通 user 内容；其他未知角色在 HTTP
  前拒绝。Gemini 默认“非 assistant 当 user”不能当映射通过，也不能用删除该映射作等价突变。
- 可信策略只有来源描述中的位置及服务端固定常量；资料不进入 system/systemInstruction。
  应用内轮次、goal 只认真正 user，资料仍计 token，估算时包括新视图角色长度。
  协议层资料也是 user 内容，模型无法仅靠角色区别真实提问与资料；这是残余注入风险，
  不是授权机制，不宣称“模型不会被资料伪造用户”。
- 投影不能因为原“开头连续 system”循环而漏掉 index≥1 的资料；不得插入到 assistant
  tool_calls 与配对结果之间，不得放到最后真实 user/工具结果之后改变 Mock 的目标。
  current turn / policy 顺序、工具配对与容量拒绝仍按 H1 保留。
- 模型视图里的 CONTEXT_DATA 不能因为不再是 system、又位于最近 user 之前就被旧
  compactor 丢掉。ContextManager/compactor 要按显式类别保留本次必需资料及可信策略，
  不靠正文关键字保留；装不下仍明确拒绝，不能静默裁掉/截断。过往普通轮次仍可按 H1
  丢弃，资料不能伪造当前轮边界。这条要用“资料在最近 user 之前 + 压缩确实触发”反例固定。
- 知识 Run 的 rawMemoryVisible=false：CanonicalMemoryIndexer 必须不写原始记忆索引，
  分层记忆投影维持 NOOP。这条应有反例，不新增永远走不到又破坏回退的 CONTEXT_DATA 枚举。

### 4. 更新后的验收与范围

1. 先用 05ab7a0 的旧 writer 产物提交 RuntimeMessage / checkpoint / canonical 历史的
   原文金样和固定 SHA（已采集见 context-authority-goldens-05ab7a0/README.md）。新 reader/writer、det_ 回读、model:N
   prefix replay 必须逐字兼容；不能用新实现即时计算再回填 expected。
2. Chat 公共入口覆盖三家 generateStream；同步 generate 从各 adapter 的生产入口验证。
   生产同步还存在意图分类、Workflow LLM 和健康检查，不是“生产没有同步”。这些路径没有
   context_data；WorkflowExternalNodes 把变量插入 systemPrompt 的同类问题列为范围外
   已知风险，不能声称本切片已治理所有模型入口。
3. 旧无资料/旧知识/显式续接分别做正例；版本/引用/正文不一致做反例。重点单列
   model:N 已提交但 checkpoint 落后、含待执行工具的场景：历史响应可回读且不重调模型，
   未核实来源时新工具零调用、不进入 COMPLETED；合法来源正常继续。分别覆盖 tool replay、
   CLARIFY/INTERRUPT skip、纯 final、失败 Run 的 resume 拒绝和新 Run 不携入悬空调用。
4. summary/catalog、近期轮次、goal、单轮工具压力、无 user、多个显式可信策略、
   知识 Run 不索引、资料不丢失及工具配对逐项测试，全部断言 canonical 未改变。
5. 突变分别移除 RAG/summary 降权、把资料计入真实 user、绕过未知来源门禁、绕过配对
   和容量保护；三家 fixture 直接断言完整请求体。adapter 故障注入为明确错映射到 system
   或允许未知角色，不用 Gemini 默认分支吸收的等价突变。先证明反例因目标断言变红。
6. 分别注入确定性不一致、数据库临时故障、取消、到期、suspended，验证新终态映射与
   原控制优先级；移除专用映射会红在 reason，移后入口门禁会红在 model.started/调用次数。
   多事件相同/不同/零匹配、state 冲突、旧 chunk 清理、新 Run 崩溃恢复逐项覆盖。
7. 单列关停交错，使用生产的实时 lifecycle control，不伪造暂停信号延迟：读取期间
   stopping 置位时，断言后续模型/工具零新调用；这些早期检查不要求各自通过持久状态
   的独立突变证明。另将接缝固定在 finishTerminal 事务内，findByIdForUpdate 的
   thenAnswer 返回之前置位 stopping（参照 RunWorkflowControlTest 的锁行接缝）；
   Result 与异常两条路径都须已经经过其前置处理，不能在 execute catch 之前置位。
   分别覆盖 MISMATCH 和 UNAVAILABLE：要求写可恢复 interruption、保留
   RUNNING，撤掉终态提交边界检查必须红在持久状态/reason 断言。加非关停来源失败、
   已持久取消、仅提交前迟到超时三组对照，分别保持原来源失败、CANCELLED、原来源失败。
   迟到超时对照也在 catch 之后的上述锁行接缝注入，不绕过原异常路径的控制检查。
   保留 RunWorkflowControlTest.computedWorkflowOutcomeIsNotDiscardedWhenShutdownStartsBeforeReturn
   的 SUCCEEDED/FAILED/TIMED_OUT 对照，不能把既有已算出的普通结果一律改成暂停。
   检查通过后才置位的交错允许 FAILED 提交，不宣称消除了整个提交窗口的竞态。
8. 覆盖 failed+completed 与 citationCount=0 的 completed 排除、无 started/重复 completed、崩溃后新 started，
   以及同作用域成功复用/来源绑定改变拒绝复用/新作用域强制重读。新 Run 的候选不能
   绕过首次核实；失败提交前崩溃可重新核实，提交后不得自动恢复。

### 5. 发布与回退边界

此设计不授权现在部署。上线时不采用新旧实例混跑来宣称全覆盖：旧实例仍会把资料当策略，
即使新实例实现正确也挡不住旧实例接手 Run。切换需停止旧实例接收新工作、处理在途与恢复
归属后再统一启用新版本，并验证所有入口都已切换；具体切换方案另行 review。
回退旧 jar 数据可读不等于新安全语义兼容，新 reason 的显示/导出也需检查；回退后不能让
旧恢复逻辑重新执行本版已终止的失败 Run。无批量补 tool result、重写 canonical 或删证据。

仍只是设计：不发布新角色、不批量改历史、不部署；实施由已登记的
CONTEXT-AUTHORITY-001 逐项证明，登记不等于实现或验收。

## 以下为 164abe4 旧方案与清点记录（已被上述修订替代）

源码核对基线 `5ff8996`，与六范围受测 05ab7a0 的产品树相同。课程依据是已经精读的
C139、C075、C088、C090，具体原文和纠偏见 reading-notes.md；本文件是本项目方案，
不是课程已经给出的实现。当前只读调查和设计，没有新增测试运行，没有改产品。
先独立 review，再注册一个原子任务并做先红后绿；不同时推进多个产品切片。

## 要修的事实，不扩写成安全认证

1. RunApplicationService.execute 把 knowledgeContext 放进 RuntimeMessage.system；
   内容包含检索原文，不因来自已发布语料而成为用户/管理员的策略授权。
2. LayeredContextMemoryService.project 把摘要 JSON、目录预览与固定使用规则拼进
   一条 system。StructuredSummaryService 的摘要由确定性规则生成，并非 LLM；
   constraints 关键词筛选包含工具/旧对话正文，字段名不构成可信授权。
3. 三家 adapter 都会把 system 作为指令区：OpenAI 直接发送 role；Anthropic
   聚合成顶层 system；Gemini 聚合成 systemInstruction。当前正文因而实际进入
   高优先级字段，不只是字符串中出现了「system」这个词。
4. H1 只保证已有 system 不被压缩器漏选/截断，不能解决上游把资料放进 system。
   MemoryDeliveryPolicy 对知识门禁 Run 的原始记忆保持拒绝，此修复不能放开它。

验收目标是**来源正文不进入协议策略字段，应用的真实用户轮次不被资料伪造**。
不以一次模型没有服从注入作为证明；低优先级资料仍可能影响模型，确定性工具授权、
证据门禁、内容/出站控制仍不可省。本文不新增 DLP、凭据代理、写工具或自动审批。

## 为什么不能只改成 user

| 读取角色的地方 | 简单改 user 的影响 |
| --- | --- |
| LayeredContextMemoryService.recentTurnCutoff | 资料被数成真实提问，近期轮次边界变化。 |
| DeterministicCheckpointCompactor | 最新资料可能变成当前轮起点，真实问题/工具配对被错误裁掉。 |
| QueryLoop.goal、StructuredSummaryService 的 goal | 资料可能被选成用户目标。 |
| CanonicalMemoryIndexer | 资料会被标成 USER_MESSAGE；随意加未知角色又会落入 MODEL_OUTPUT。 |
| CanonicalDetailReader | RuntimeMessage 重序列化参与摘要校验；加字段/改旧 JSON 可能破坏现有 det_ 引用。 |

同时，不能冒造 tool_result：没有匹配 tool_call 的结果在 Gemini 已会被拒，
也会破坏真实工具调用/结果的对应关系。无论消息角色还是标签，都不是事实真实性的证明。

## 推荐方向：内部资料角色，协议边界明确降为普通内容

候选内部角色 `context_data`，保留 RuntimeMessage 现有四字段形状；不加一个默认
写入旧消息 JSON 的字段。仅受信应用组装器产生这种角色，不让外部正文/模型通过
字符串标签声明权限。RAG 和历史投影正文使用它，真实策略仍用 system。

- 适配器显式把 context_data 映射为用户侧普通文本：OpenAI user、Anthropic user
  text block、Gemini user text part；绝不产生 tool_call_id/tool_use_id 或 system 字段。
  三家同步/流式两条请求构造都要覆盖，不能只测一个 toMessage 辅助方法。
- 固定的资料使用规则与来源正文分开。规则只含服务端常量，不能格式化拼入检索值、
  summaryStatus/正文/目录。正文保留来源引用和状态，但状态不表示 VERIFIED 策略。
- 轮次和 goal 只认真正 user；context_data 不计入。分层投影与 compactor 明确保留
  当前资料，并维持原工具配对、可信 policy 顺序和最新 user。资料仍计入容量，
  装不下按已有超限出口拒绝，不把它升回 system，也不静默截断强制规则。
- CanonicalMemoryIndexer 若遇到该角色，必须有明确的 CONTEXT_DATA 分类，不能
  回落为模型输出/用户消息。V11 的 kind/role 是 VARCHAR(32)，没有枚举 CHECK；
  不需要为字段长度改变表，但 Java 枚举的旧二进制兼容风险仍需单独处理。
- canonical、det_ 源引用及旧摘要原文不做批量重写。新的模型视图投影与历史原文
  必须区分；固定旧四字段 JSON+SHA 金样应在读写后逐字一致。

内部新增角色不是公开模型协议的通用扩展；任何一处漏转换都会产生无效请求。
实现前须查完 RuntimeMessage 的所有读写点，不用 Gemini 当前的「非 assistant
都按 user」默认分支作为已经适配的证据。Mock 也要保持最终问题/工具结果不变。

## 旧 checkpoint：必须在实现前评审的兼容规则

新 Run 可准确标记来源；旧 checkpoint 中的资料与策略都是 system，不能靠
`[HIFY_CONTEXT_MEMORY]` 或「包含必须」之类正文前缀判断信任。

拟在普通 Chat 的恢复边界，利用**已固定 AgentVersion + 旧生产组装顺序**识别旧
前缀：首条 system 必须与该版本 instructions 逐字一致，后续旧 system 不能自动
继续获得策略权限；已核实由旧知识前缀生成的部分只在模型视图降为资料，服务端
固定资料规则重新由代码提供。未知排列/来源无法核实时拒绝恢复，模型零调用，
记录明确的兼容失败，不凭名称或任意哈希“猜成可信”。

这条规则仍是待 review 的方案，不是兼容性已经成立：必须覆盖无知识旧记录、
知识绑定旧记录、显式新 Run resume、版本缺失/不匹配、多个旧 system、伪造资料
前缀和旧引用回读。旧持久消息/摘要不能被覆盖；若在新 revision 保存转换结果，
必须保持旧 revision 的 detail 可读，并记录语义版本边界。不能把改变旧 bytes
但更新 hash 的行为称为“原有引用不变”。

回退风险：旧 Java 枚举不认识 CONTEXT_DATA，旧 OpenAI/Anthropic adapter 也不认识
context_data。故不能承诺新 checkpoint/新索引写出后能直接回退旧 jar；部署前须
给出兼容 reader 或明确停止/隔离新格式记录的回退步骤。本轮不部署，不能用这点
阻止本地设计，但它是上线前置条件。

## 具名反例矩阵（将先在旧实现红，再写修复）

1. **RAG→三家真实 HTTP fixture**：Chat 公共生产入口实际调用 generateStream，
   用它覆盖三家流式路径，资料含伪造策略标记；同步 generate 的三条路径通过
   生产 adapter 入口单独验证，不能称为 Chat 同步链路。六条请求构造都检查
   策略字段只含真实 instructions/固定规则，资料仍在普通内容，最新问题不变；
   有纯无资料正对照。fixture 不是真实模型安全评测。
2. **历史投影**：含旧工具注入文本、摘要 constraints 和目录 preview；超过 recentTurns
   才实际进入投影，检查资料没有出现在 system、recent cutoff/goal 不被伪造。
3. **压力与配对**：多轮、单轮六批工具、最新并行工具结果、没有 user、多条可信
   system，逐条验证内容/顺序/call-id、原 canonical 不变和最终容量检查。
4. **序列化金样**：旧四字段 RuntimeMessage、旧 checkpoint 与 det_ 原文/摘要固定
   SHA；读写逐字一致，旧引用回读不因新增内部角色改变摘要。
5. **恢复正反例**：按上节矩阵，未知旧来源拒绝且模型零调用；可迁移的旧来源在
   第一次请求前已降权。禁止只用新代码创建再恢复的自洽用例代替旧金样。
6. **独立突变**：分别把 RAG、summary/catalog 改回 system；让 context_data 被计作
   user；漏掉每个 adapter 的映射；使 indexer 回落 MODEL_OUTPUT；旧恢复跳过来源
   核对。每项应红在目标断言，而不是编译失败、未知协议或数据库枚举错误。

测试设计完成后登记任务与清单，在协调窗口运行；保留原始红灯、具名突变、六范围
回归和固定提交只读 review。现阶段尚无这些新反例的运行结果。

## 2026-10-07 读写路径清点（d68bf22，仅静态核对）

检索入口是所有 main Java 的 `.role()` / `getRole()`，并向上追到请求构造、
序列化和恢复写入。它是实现前清点，不是宣称文本搜索证明了调用图完备。

| 路径 | 已核实的行为 | 必须保留的界线 |
| --- | --- | --- |
| RunApplicationService.execute / prepareResolvedCheckpoint | 新 Run 组装策略、RAG、对话；显式续接复制旧 checkpoint.messages 后追加真实 user | 不把资料计为新用户，也不靠拷贝后改摘要掩盖格式改变 |
| QueryLoop.run | `messages` 是 canonical 列表；先用它 replay；未命中才 prepare，再把 prepared.messages 传 generateStream；结果追加到原 messages | 模型视图降权不应修改 replay 使用的旧前缀；公共 Chat 不走同步 generate |
| ContextManager / LayeredContextMemoryService | 分层摘要和目录加入投影列表；prepare 返回的是模型视图，不直接替换 QueryLoop 的 canonical 列表 | 当前摘要投影里的 system 不等于已经写入 checkpoint 的 system；两种旧数据来源不能混同 |
| CommittedHistoryWriter.replay | 原 JSON 摘要核验后，逐项比较完整 prefix，要求历史恰好多一条消息 | 仅把旧 system 改成 context_data，也会造成 prefix mismatch；旧恢复必须在 model view 降权，不先改 canonical 再 replay |
| CanonicalMemoryIndexer / CanonicalDetailReader | indexer 按消息 JSON 生成摘要，reader 按原 revision 回读并重序列化核验 | 未知角色当前会落 MODEL_OUTPUT；新资料分类必须显式，旧 revision/digest 不改 |
| ContextTokenEstimator / ToolResultArchiver / ContextManager.archiveEarlierResults | estimator 计算角色、正文和工具字段；归档只处理 tool，压力救援保留最新工具批次 | 新资料仍计预算；不得伪造 tool 以借用归档路径，call-id 配对不变 |
| Compactor / recentTurnCutoff / 两处 goal 选择 | compactor 保留 system 和最新 user 以后；分层 cutoff 与 goal 识别真实 user | context_data 需明确保留策略，不能因降权而被无声裁掉，也不能计作 user |
| OpenAI / Anthropic / Gemini | 同步与流式分别构造请求；各家两条路径共享自己的 toMessage/toContent | 分别覆盖两个构造入口；不能只测私有映射方法，不能依赖 Gemini 未知角色默认 user |
| MockModelClient | 读取列表最后一条；tool 分支之外按末条正文识别示例请求 | 降权后不能把资料放到真正用户/工具结果之后，改变 Demo 目标 |
| HistoryRecallService / MemoryController | 返回来源 role/kind 与原文/预览，不把它们自动变成执行权限 | 新 kind 的 API 和旧 reader 回退风险需明确，不因 DTO 能序列化就宣称旧二进制兼容 |

因此补充一个恢复反例：旧 model:N 已提交、checkpoint 尚落后，旧 canonical
含 RAG system。恢复应继续验证原前缀并复用已提交响应，后续真正的新模型请求
才使用降权视图；模型重放/工具重放都不能因变更角色被误判历史冲突。
这里没有运行该反例，仍须先固定旧原文与 SHA，再实现并验证。
