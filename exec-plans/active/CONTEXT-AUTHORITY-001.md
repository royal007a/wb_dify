# CONTEXT-AUTHORITY-001

状态只以 harness/tasks.json 为准。用户课程研究及逐项优化目标下的本地实现切片，
不授权部署；原版 132 已发布的版本不会随此工作树改变。设计依据：
`docs/research/jikesummary-20261006/context-authority-design.md` 修订契约（至 5030714）。
859b428 设计经 mymacclaude 独立只读确认无 P1/P2、可登记实施；5030714 仅明确 P3
接缝与迟到超时边界。不能将后半段历史旧方案重新当作实施契约。

## 问题与完整结果

当前检索原文、分层摘要和目录预览被装进 system，三家模型协议会把它们当策略。
修复必须使来源正文不进入协议策略字段，同时保留 canonical 原文、重放和引用兼容。
仅将 system 全换 user、只修一个 adapter、只测试投影辅助函数均不满足本任务。
低优先级资料仍可能影响模型；本任务不是语义真实性或完全防注入保证。

## 实施顺序与可审查产物

1. 在 **05ab7a0 的独立 git archive** 上执行旧格式采集器，保存原始消息、生产
   persistCheckpoint 写出的 messages/plan/context 字段、CommittedHistoryWriter
   写出的 canonical JSON/digest，以及由真实 indexer 生成的 det_ 身份与回读正文。
   记录 baseline、采集器 SHA、命令/退出码、原文 SHA；不读真实用户/生产数据库。
   普通形态与知识形态分开；合成序列化样例不冒充来源核实/数据库恢复验收。
   先固定金样，再实现产品改动；不能生成后以新代码重写 expected。
2. 增加仅内存的来源描述/核实 port、两类失败及终态映射，明确所有 identity 构造点。
   应用层从固定版本、合法来源链、检索事件段和 canonical/contextState 构造证明；
   用预算控制包住读前读后/异常分类，成功证明只缓存于一次 execute。
3. QueryLoop 保留 replay 在前：真正新模型调用之前、响应后整个终态/工具处理之前
   都检查来源；事务锁行后的来源失败关停重查与持久取消优先保持既有恢复语义。
   未配对工具调用保持原始事实，拒绝之后不再进入模型，不补造错误结果。
4. ContextManager 和分层投影承担模型视图转换、可信规则分离与资料保留；QueryLoop
   不实现裁剪。compactor、goal、recent cutoff、Mock 最后一条语义及 token 计数逐项核对。
   三家同步/流式协议统一显式角色白名单，OpenAI-compatible 共用路径也记录覆盖。
5. 完成设计第 4 节八项全部矩阵、先红后绿与独立突变；新增 Maven 类同步期望清单。
   共享机器测试窗口先协调，不通过降低超时/断言或跳过 PG 获取绿灯。
6. 在固定产品提交运行显式六范围、保留首轮失败和重跑；提交证据供 mymacclaude
   独立审查。review 修订后重新判断应重跑范围，不能把旧全量当最终 HEAD 全量。

## 验收路由（设计完整要求不可省略）

| 设计项 | 生产路径 / 证据 |
|---|---|
| §4.1 旧格式 | RuntimeMessage、RAS checkpoint 字段、CommittedHistoryWriter、CanonicalDetailReader；固定原文/SHA/旧 writer 来源 |
| §4.2 六条协议 | Chat 公共 Run 入口 → generateStream；adapter generate 同步；HTTP fixture 直接检查请求体及零调用负例 |
| §4.3 重放与续接 | model:N 已提交/checkpoint 落后、待执行工具、tool replay、CLARIFY/INTERRUPT、final、新 Run 历史；H2/PG 及持久状态 |
| §4.4 投影不变式 | summary/catalog、近期轮次、goal、无 user、多策略、工具压力、知识不索引；canonical 保持、配对与容量 |
| §4.5 突变 | RAG/summary 各自提权、资料算 user、门禁缺失、协议错映射、配对/容量遗漏；分别红在目标断言 |
| §4.6 分类/多事件 | MISMATCH/UNAVAILABLE/取消/到期/关停、歧义/零匹配、state 冲突、chunk 清理、新 Run 恢复 |
| §4.7 关停交错 | finishTerminal 事务内 findByIdForUpdate 返回前切换 stopping；生产 control，撤掉提交边界检查可变红；普通结果不回归 |
| §4.8 段与缓存 | failed+completed、零引用、缺 started/重复 completed、新 started；绑定变化拒绝复用、跨 execute 重读、失败提交前崩溃 |

这些是待验证要求，不是已有通过的用例。来源链与缓存测试需实际走应用层入口，
不能只造 ContextAuthority 的自洽对象；旧格式采集不能顶替新 reader/旧引用回归。

## 边界与回退

- 不加数据库迁移、不新增持久角色/枚举、不批量改历史；不删除旧金样或失败日志。
- 不重做已提交工具；外部执行与本地 commit 的未知窗口仍存在，不承诺 exactly-once。
- 新终态仍使用 FAILED，明确新 reason；失败 Run 不可续接，成功提交前崩溃仍可能恢复。
- 旧二进制能读取旧形态不等于保留新防护；回退会恢复旧提权问题，生产切换需另行 review。
- Workflow systemPrompt 插值、实时模型安全评测、出站防泄漏不是本切片已解决的内容。
- 当前测试窗口属于 CC；本轮先准备采集器/计划，不启动 Maven、浏览器或本地服务。
