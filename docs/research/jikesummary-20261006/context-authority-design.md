# 下一候选：外部资料与策略权限分离（设计草案，未实现）

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

1. **RAG→三家真实 HTTP fixture**：通过 Chat 公共生产入口，资料含伪造策略标记；
   六条同步/流式请求构造检查策略字段只含真实 instructions/固定规则，资料仍在
   普通内容，最新问题不变；有纯无资料正对照。fixture 不是真实模型安全评测。
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
