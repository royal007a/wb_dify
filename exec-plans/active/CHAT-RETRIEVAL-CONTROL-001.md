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

## 2026-10-07 04:12 门禁诊断续记（未验收）

上段是开工阶段记录。主树 c45c19a 的门禁已中止并保留红灯；开发树
11e7be5 的完整门禁也于 04:12:21 主动 SIGTERM 中止，exit 143，原因是
已有确定失败且 Mac 实测持续内存换页。不是测试通过，也不是进程失联重跑。
只停自己的 hify-verify-20261004 profile；default 和 dify 保持运行。
本次部分记录见 docs/research/jikesummary-20261006/failed-gate-11e7be5-1g/。

同一原子任务内先补窄夹具诊断，不改生产时限或业务断言：

- 索引元数据故障 mock 在 dispatch 前预构造；03:40 同轮线程快照捕获了
  Mockito 类生成位于异步写事务内。保留原 5 秒等待、事务检查、FAILED
  状态、脱敏和旧 chunks 不变的断言。仍需真实 PostgreSQL 单方法复验。
- 安装夹具 TimeoutExpired 在临时目录删除前附加有界的假命令阶段/状态；
  不打印参数/环境，不改变异常对象、10 秒限制或 EPIPE 清理断言。先有
  scratch 6 项单测、断言红灯及原用例 4 子场景隔离重放，之后验证仓库版本。
- 关停恢复路径增加阶段诊断，不删除原 60 秒限制或任何成功/恢复断言。
  当前红灯包含底层 COMPLETED vs TIMED_OUT 和计算完成 latch 失败，不能
  只按资源问题忽略。新契约规定同 Run 重启不重置预算：补充验证应区分
  预算内恢复与真正过期恢复，不能把旧成功用例改成接受任意终态。

完整六 scope 及独立 review 仍是完成条件；task 保持 pending，不部署。

## 2026-10-07 04:46 窄复验续记（完整验收仍未完成）

7764bf2 的 Java 测试源码已 test-compile 通过，原始日志见
fixture-diagnostics-20261007/java-compile.txt（不执行测试）。
在相同 Java 源码、HEAD 9d22af5 上，以新私有 reports 目录单独运行
WorkflowKnowledgeReviewPostgresTest#indexingMetadataFailurePersistsFailureWithoutDeletingExistingChunks，
真实PG 1/1、零fail/error/skip，5秒等待和持久化断言未放宽。
证据见 docs/research/jikesummary-20261006/indexing-postgres-7764/README.txt。
耗时近9分钟，其中Spring启动约298秒；不能凭这一窄绿灯关闭完整关停恢复红灯。
下一步仍需预算内恢复与已到期恢复分开验证，并保留原COMPLETED和60秒条件。

## 2026-10-07 05:00 恢复反例补强（待运行）

为 H2 / PostgreSQL 各新增真实 context.close → 同库重启的已到期反例。
首实例真实进入阻塞模型并由生产停机中断；断言 RUNNING、无用户取消、
已有 run.interrupted 和持久 checkpoint。只在已关闭的隔离夹具数据库中，
把这一行 created_at 回拨 300 秒，再重启第二实例；必须 TIMEOUT / TIMED_OUT、
模型零调用、无 assistant / delta / checkpoint.restored，原 checkpoint 不变。
这不是生产时钟覆写，也不是把旧成功测试改成允许任意终态；原正例继续
要求 COMPLETED、回答和恢复事件，并补模型恰好一次。45/60 秒类/方法上限
及原来的 10/5 秒 latch 等待不变。清单同步增至 H2 7 / PG 3。
未运行前只算新测试代码；完整门禁依然未通过。

05:15 结果更新：上述 H2 两方法已运行，2 tests / 0 failures / 2 errors / 0 skipped，
Maven exit 1。过期反例走完业务断言但仍违反原45秒限制；正例另有10秒终态等待
失败（suppressed assertion），不能忽略。日志、报告、主线程快照已保留在
recovery-expiry-9a6d8b8/；新增PG方法和这组突变未运行。本轮没有再开验证VM，
不因窄反例的局部断言经过而把任务改为完成。后续继续诊断与研究，不盲目重跑。
