# 聊天知识预取的取消和截止控制

2026-10-07 16:05:17：固定 05ab7a0 六范围门禁自然退出 0，runner 已将本任务
记为 completed；本计划随机器状态归档。最终证据仍须独立只读复核，未合并 main、
未部署，研究总目标未完成。下方均为按时间保留的历史阶段，不是实时任务状态。
本次计数、源码身份、旧失败保留和未验证边界见
`docs/research/jikesummary-20261006/full-gate-05ab7a0.md`。

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

## 2026-10-07 05:24 单调时钟算术反例（同一预算切片）

静态发现 ExecutionControl 把 Long.MAX_VALUE 用作无限哨兵，并按绝对 nanoTime
相加/比较。Java 17 System.nanoTime 契约允许负起点，要求以差值比较以避免溢出；
官方依据：https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#nanoTime()
这不是前述真实关停红灯的已证实原因，当前机器时钟起点也未证明命中该边界。

先仅加入包私有的时钟依赖入口（公开工厂仍固定 System::nanoTime，无配置项、
无全局替换），在未改算术时新增负起点、跨符号、截止值恰好MAX、父子剩余
预算的确定性测试并保存红灯。再改为起点/时长及时间差计算，显式区分无限。
保持取消/停机优先级、已有超时时长和所有生产公开API；验证所有原用例、边界
正反例及必要突变。该子步骤不替代真实PG和完整六scope。

### 红灯检查点（按用户要求拆分提交并推送）

2026-10-07 05:26:03 CST，Java 17 离线运行 hify-common 的
ExecutionControlTest：10 项，5 项断言失败，0 error，0 skip，Maven exit 1。
失败的都是本次新增边界反例；原有 5 项通过。编译成功，不是编译错误导致红灯。
命令为 `mvn -B -o -pl hify-common -am -Dtest=ExecutionControlTest -Dsurefire.failIfNoSpecifiedTests=false -Dhify.test.reportsDirectory=<fresh>/red-reports test`。
原始日志保存在 `docs/research/jikesummary-20261006/clock-boundary-red/`，
其中 maven.log SHA-256 为 `0243dd5ffc1c908484ff7689e317d3b14b204acfac5ddfbe2b2dd6bc00bd3246`。
此检查点只增加测试时钟入口和失败回归，尚未修改截止时间算术；不是修复完成，
不合并 main，不部署，不更改任务的未验收状态。

### 算术修复与验证边界

改为 `startedNanos/timeoutNanos`，只有内部 -1 表示无限，Long.MAX_VALUE 的有限
时长不再被哨兵吞掉。经过时间使用 `now - startedNanos`，跨符号时仍按差值判断；
子控制读取一次当前时刻，以父级剩余时长和请求时长的较小值创建。已到期父级
产生零剩余时长子级，不能复活；附加取消/停机信号不重置起点。负经过时间属于
时钟倒退或超出 JDK 小于 2^63 纳秒的区间，按到期处理；不宣称支持任意长时间轴。

2026-10-07 05:33:55 CST，同样 10 条测试已从 5 个 assertion failure 变为全绿
（0 fail/error/skip）。另补 3 条保护：最大有限 Duration 及转换溢出、复制控制时
不重置起点及倒退时钟、无限父级派生有限子级。hify-common 全模块测试另行运行，
其结果不得以这次 10 条窄测替代。

尚未修改的独立绝对时钟比较：CircuitBreakerService 的本次调用 SLA、LlmHttpClient
的 HTTP/stream SLA、RunEventBroker 的 SSE 生命周期。它们不是本次 ExecutionControl
修复的覆盖范围，需要各自的确定性反例后再改；不能称全链路时钟风险已消除。

验证原文在 `docs/research/jikesummary-20261006/clock-boundary-validation/`：

- targeted-maven.log：10/10，SHA-256 `6a1a99d9f23ee4950b5d530e22bf0fbfe852a7c898eded3791d74369a6093e01`；
  targeted-junit.txt：`539db9b07a099eff6e22fb9486d5a832cb86691abd552e0e7648b3a51c97b6fd`。
  当时修复代码与 dd3a83f 的生产类相同，但还没加后面 3 条保护测试。
- common-run/maven.log：2026-10-07 05:36:33 CST，78 项、1 failure、0 error/skip，
  SHA-256 `94ea4457557c99a976197a7300d8816ea46ecead9e421267f1d81653db0521a4`。
  从同次 10 份 fresh XML 汇总核对；提交其全部文本报告，XML 留在隔离运行目录。
  命令为 `mvn -B -o -pl hify-common -am -Dhify.test.reportsDirectory=<fresh>/reports test`，
  源码对应 dd3a83f，构建后没有修改产品或测试源码。
- 新增后的 ExecutionControlTest 13/13。失败是原有
  `CircuitBreakerServiceTest.stopsAttemptChainAtOverallDeadlineAndInterruptsTheWorker:149`，
  1 秒内未观察到 interrupted latch；原 50ms 总超时及断言均未修改。
  测试没有先确认 operation 已启动，队列先过期是待验证的解释，不是本日志证明的根因。
  本次不修改该断言、不把一次重跑通过当作排除功能问题，也不归咎于资源环境。

上述结果只说明新算术反例由红转绿，不能支撑 hify-common 全绿或六 scope 验收。

### 05:40 中断红灯的分阶段定位

保留原有 50ms deadline、5 秒模拟阻塞与 1 秒中断等待，在原测试中仅增加
operation-entered / operation-interrupted / caller-timeout 三个阶段、进入计数和经过
纳秒的诊断。新建独立“排队直至到期”反例：Executor 只捕获 Runnable 不调度，
调用返回 TIMEOUT 后再运行已取消的 Runnable，要求 operation 零调用，熔断成功/
失败计数均为零。该用例区分“没启动就到期”与“启动后必须中断”，不能替代
原来的运行中断言。本轮先跑带诊断的固定测试类，不以重跑变绿抹掉前一次失败。

05:42:01 CST，该类 13/13、零 fail/error/skip，Maven exit 0。本次日志记录
operation-entered=0.920041ms、operation-interrupted=145.384666ms、
caller-timeout=146.250583ms（相对测试观察起点，非精确生产 SLA 测量）。
这证明本次确有运行中的中断，以及另一个用例的排队拒绝行为；不能追溯证明
上次没有 entered 诊断的红灯是排队造成。没有改 CircuitBreakerService 产品逻辑。
原文 `worker-deadline-diagnostic/maven.log` SHA-256 为
`e179e29569922c4f8d3992be9abb825750151b39fe05cd977572077afc16c8fb`。
命令 `mvn -B -o -pl hify-common -am -Dtest=CircuitBreakerServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Dhify.test.reportsDirectory=<fresh>/reports test`。
common 78 项那次仍为失败；不把此单类重跑覆盖为全模块通过。

### 05:47 HTTP/熔断单次预算使用同一时钟

继续同一个预算任务，不修改 SSE broker 的传输生命周期。先添加 4 个方法：
熔断器本地20秒到期/父100秒仍有效、及时19秒对照及父100秒到期不记供应商失败；
真实本机 HTTP 完成后模拟65秒与64秒；真实本机 SSE 在首个 delta 后模拟125秒，
后续 delta 禁止交付。注入时间只通过已有包私有 ExecutionControl 工厂，不新增
生产配置；测试证明本地预算应使用父控制的时间域，不是假称在真实世界等了65秒。

拟用 parent.boundedBy(localTimeout) 替代两类里的绝对 nanoTime deadline。熔断统计
仍以原始 parent 判定取消/到期不采样，以本地到期判定供应商 SLA timeout；不能
把本地超时也一概归为用户预算耗尽。HTTP 的错误类型和5/60/65、120/125秒上限不改。
保持现有中断测试与全部断言，先保存新测试红灯后再改产品逻辑。

红灯（6a7b244，产品仍为前版）：4 个新方法，3 assertion failure、1通过、0error/skip，
05:47:51 CST Maven exit 1。日志 `local-budget-red/maven.log` 的 SHA-256 为
`bbe41399809d5223b432d9f42005ba689b52cee0699ce38d3cb433a5fc92d6bf`。
它验证本地时限没有使用被注入的父控制时钟；不是在实际 JVM 上自然等到 nanoTime 回绕。

修复 363ecce 后，05:49:38 CST hify-common 完整10类共83项，全为通过，零fail/error/skip，
Maven exit 0，fresh XML 逐类加总与终端一致。命令
`mvn -B -o -pl hify-common -am -Dhify.test.reportsDirectory=<fresh>/reports test`。
日志及全部文本报告保存在 `local-budget-validation/`，maven.log SHA-256
`128318ede64712a81865fa81a6f3f6f990e25980aa6d44bb76cf5f5a6345c011`。
Control 13、CircuitBreaker 15、HTTP 17、ProviderDeadline 6、Shutdown 4 均包含在83内，
不再重复相加。产品/测试源码在构建期间未改；只在构建期间提交了相同产品代码。

原50ms中断用例本次也通过，但不能由这个绿灯声称已定位/根治先前的偶发失败。
此前 common 78项失败与后续诊断原文继续保留。本次只覆盖common模块，不覆盖
真实PG恢复、应用装配、完整backend/runtime/eval/frontend，也不是六scope验收。
RunEventBroker 的传输时钟仍未修改，不宣称所有 nanoTime 比较已统一。

### 06:10 非Docker四范围验收

固定3ebe106、工作树源码干净，`verify.sh --scope harness,runtime,eval,frontend`
同一进程完成，exit 0，schema3 invocation `fe6bb869-1069-426d-b4aa-f75aec62788a`。
原始日志/manifest和逐类摘要见 `four-scopes-3ebe106/`，verification SHA-256为
`5475b67899a78f78aa568993964517e85e1ac115af00c5526ad1c8ac90886144`。
Harness Python实际84项，runtime 35项、eval 24项均零失败/错误/跳过；前端类型检查
与构建通过，保留大包体警告。没有浏览器测试。runtime中的3类H2集成不是此前的
关停重启反例，故不能据此关闭那次红灯；backend和migration没有在本轮执行。
完整六scope与只读review仍是完成条件，任务状态不变；未合并main、未部署。

### 13:23 真实H2重启正反例复验

固定0087ffc、源码干净，原RunShutdownIntegrationTest两条模型重启方法本次2/2通过，
Maven exit 0，45秒类级时限及10/5秒断言未改。原始日志和摘要见
`recovery-recheck-0087ffc/`，日志SHA为
`5a873498db1fbb7179a902323fa6437593d3c3d663661506b6a5b0ed8aa7369a`。
这次真实执行了及时恢复一次模型调用和到期拒绝恢复零调用；旧05:15两项ERROR保留，
不能从一次绿灯倒推旧失败根因。不是全类、PG或完整六scope，仍不完成任务。

同轮收到mymacclaude对固定2d66dea之前代码和四范围证据的只读复核：列出的诊断、
重启测试设计、elapsed时钟与子预算、四范围计数可以接受；未运行Maven/浏览器/服务。
阅读文档仅核对未改src/main，未逐章对PDF，不计为内容审查通过。SSE broker独立
时钟仍是范围外P3。新H2复验与C177笔记另交固定范围，不能借旧review覆盖。

### 14:20 六scope完整运行红灯

固定e7d43b1，run 88abd54f自然结束exit1，全部scope执行完毕。harness84通过；
migration126项1error；backend770项1failure+1error；runtime35、eval24与前端
typecheck/build通过，零skip/flaky，预期类齐全。见
`docs/research/jikesummary-20261006/full-gate-e7d43b1.md`及原始run目录。
失败分别是旧空目录结构契约、PG/H2过期恢复首次上下文启动越过方法超时。
原时限/断言不变；独立窄绿灯与同轮backend PG3/3不覆盖这些红灯。
结构契约替代方案已独立review，补丁只在scratch准备，还未应用到此源码。
14:20:38停掉自己的验证VM并交还重测试窗口。任务未验收，不部署。

### 结构契约替代补丁（完整验收仍待续）

在保留完整红灯的e5c244d之后，替换旧空目录断言，不建空壳；原reactor/必需依赖
断言保留。新增test-only源码解析器和20个正反例，检查marker/port声明、历史包根、
拆分包/重复类型，以及三条有限依赖规则。Chat使用历史入口。边界见ENGINEERING§1.1。
独立JDK17/JUnit离线运行两个结构测试类22/22，无Spring/Maven/Docker/浏览器。
这不是backend或完整门禁通过；没有改任何关停测试、生产类或45/60秒时限。
干净archive验证及删除防护的突变另留原始记录，之后交固定范围review。

### 慢启动分阶段诊断准备

结构契约已在16b1959/0ad6070留存干净archive22项与6项突变证据，仍未重跑Maven。
另在关停测试加默认关闭的startup-diagnostics：有限buffer、stage/bean白名单、
最慢12项与线程CPU/墙钟；诊断RuntimeException不改原结果/异常。helper6项离线
通过，没有实际启动Spring。45/60秒和全部业务断言未动，等待协调窗口做真实H2/PG
受控复验；不把新增诊断当作根因或修复。详见startup-detail/README.md。

### 15:04 H2诊断窄测仍红，记录窗口重叠

固定716784d，Maven自然退出1：结构22项、诊断6项通过；H2及时恢复正例通过，
到期恢复反例仍越过原45秒，合计30项0failure/1error。原日志、GC、两个线程栈
与双方时间线已在d3ae0f3留存于startup-recheck-716784d/。hify-cc随后确认其
后端/vite/e2e在约15:03:50至15:05:57活动，本轮不能当作独占性能测量。
不从重叠或单点线程栈断定根因；未改时限、断言、生产实现或门禁状态。
之后明确将约20分钟窗口交给对方的定向测试、Maven与中间提交编译；在其
确认结束之前，本方只做静态阅读和证据整理，不启动重测试。

### 15:19 同一测试源码 H2 窄测通过，根因未确认

对方明确交还窗口后，固定 dd44225、干净源码，同一 30 项/相同堆与诊断参数复验，
自然退出 0，30/30，无失败、错误、跳过，驱动 27.263 秒；两个恢复方法均抵达原业务断言。
相对 716784d 只改了文档与证据，没有放宽 45/60 秒时限或移除 AOP，没有性能补丁。
原失败记录保持不变。详见 startup-recheck-dd44225/README.md，含 reviewer 补充的
近似时间线：首次失败很可能早于其第二轮 e2e，不能将红灯简单归因于负载重叠。
PG、完整 backend、六 scope 尚未复验，本任务未完成，不合并 main、不部署。
之后应对 CC 的明确申请，将下一轮约 30 分钟重测试窗口交给其修复回归；对方明确
交还前不启动本方 Maven、浏览器或验证 VM，不按预计时长自动接管。
