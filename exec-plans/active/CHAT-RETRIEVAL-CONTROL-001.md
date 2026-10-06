# 聊天知识预取的取消和截止控制

基线c45c19a，映射课程C180端到端检索预算、C129/C131单次与总量区分。当前只读证据：RunApplicationService先knowledgeCandidates、后QueryLoop；SemanticEmbeddings另建45秒控制器，仅检查线程中断/停机。最终状态可被持久cancel纠正，不代表中途没有多发请求。须先用调用计数反例，不以静态阅读代替复现。

## 契约

- 同一个Run从持久createdAt起计时，队列、检索、循环及崩溃恢复期间均消耗预算。与Workflow已有语义对齐，是普通聊天行为变更，不称纯重构。墙钟计算出的剩余值至多为配置上限，再转为单调时钟控制。
- 用户通过resume API显式创建新Run时获得新Run时限；旧checkpoint中的轮次/工具计数仍保留。不能把新Run续接和原Run重启混为一谈。
- ExecutionControl显式贯穿公共knowledge port和embedding，不用ThreadLocal；每库、每外部调用前后及结果解码后检查。QueryLoop接受同一个父控制，不重新计时。旧管理/索引API保留有界本地控制。
- embedding子控制同时受45秒与父截止限制，继承取消/停机。取消和到期不能变成来源故障；shutdown无用户取消时仍可恢复，持久cancel仍在最终行锁下优先。
- 不迁移数据库、不改发布语料/Agent/checkpoint序列化格式，不允许来源错误退化为无知识的模型回答。JDBC与CPU取消是协作式，不承诺硬墙钟返回上限或撤销已发出的网络请求。

## 实施与验收

1. 服务级测试从convergeInterruptedRuns/dispatch真实入口进入，两个绑定库，首库用latch挂起，调用公开cancel，分别释放成功和失败；断言后续库与模型零调用、终态和事件、正对照priority顺序。
2. 已过期Run恢复零检索/模型，正常Run正对照；检索消耗的时间不在QueryLoop重置。单元检查控制身份/剩余时间，HTTP反例检查真实尝试数量，不以负载敏感的窄时间差作为唯一证据。
3. ExecutionControl有界子控制：父更短/子更短、父到期、取消/停机同时发生；每个被覆盖的分支有独立反例。不得为测试增加生产配置绕过参数。
4. KnowledgeRetrievalService/SemanticEmbeddings及HTTP客户端真实组合：及时头/迟到正文、已经取消、连接错误后取消、无重试/后续KB，明确HTTP请求与TCP握手的区别。兼容已有shutdown和语料摘要用例。
5. 先红后绿与逐点突变保留原日志；预期测试清单与新增类同步更新；六scope加mymacclaude只读review，不自动部署。

## 隔离开发

当前主工作树正固定c45c19a跑六scope，故本切片使用独立goal/course-retrieval-control worktree准备测试/补丁，不与主门禁并发运行Maven/浏览器/数据库。先登记任务和反例，反例实际跑红之前不写修复；研究草案不算验收证据。
