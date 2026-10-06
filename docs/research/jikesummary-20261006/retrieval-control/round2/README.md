# 检索父控制：HTTP 窄测与七项独立突变

2026-10-07，本地隔离 javac/JUnit；产品源码为82aad0e，最终测试补强为5c412ee/b251458。不是完整Maven、Spring/PostgreSQL验收或生产部署。任务仍pending，外部review待结论。

## 真实 HTTP 的覆盖

生产SemanticEmbeddings → ProviderEmbeddingService → CircuitBreakerService → LlmHttpClient，连接独占的127.0.0.1临时端口。无模型凭据、无真实模型，不接数据库。

- 正常向量返回、HTTP请求1次；已取消时HTTP请求0次。
- 上游写出响应头和第一个正文字符后挂起剩余正文，取消后客户端以ExecutionCancelledException退出，HTTP请求1次、breaker失败计数0。这里只观测到服务端写头，不声称已直接观测客户端消费响应头。
- 上游等待后关闭连接，取消仍优先，不进行应用层重试，HTTP请求1次、breaker失败计数0。
- 预热HTTP路径后设置父预算5秒，正文挂起，父截止以ExecutionTimedOutException结束，未等独立45秒上限。测试允许30秒观测窗口，不是严格延迟性能基准；夹具finally释放正文时仍可能产生200日志，不表示迟到结果被交付。
- HTTP计数不能替代TCP连接/握手计数；本轮不宣称零TCP连接。

http-first.log：27/27，0跳过/中止/失败，退出0。此时两个控制身份测试尚未补强。七项突变完成后，再用包含补强的当前源码运行final-current.log：27/27，0跳过/中止/失败，退出0（38.848秒）。日志HEAD为当时5ffbf3e，被测未提交文件的逐文件sourceSha256对应后来5c412ee/b251458；不能把这个HEAD当成无差异构建。manifest记录当前固定源码b251458，未重写原始日志的历史HEAD。

## 独立突变

每项只把一个生产源文件的副本换进隔离javac输出，原工作树从未被突变覆盖。每次都有独立输出目录和类加载进程，依赖复用主工作树c45c19a已有target/classes和Maven缓存；未证明这些依赖构建产物的完整可复现身份，不代替全模块构建。下表失败均为断言失败，不是编译错误、NPE、夹具超时或测试未启动。

|编号|撤掉的保护|执行/失败|直接反例|
|---|---|---|---|
|M1|Run忽略createdAt，重新给满额时间|5/1|过期Run仍检索两库|
|M2|聊天向知识port传none而非父控制|5/3|取消状态未贯穿、控制身份不同|
|M3|QueryLoop重置父预算|3/3|过期run/resume变COMPLETED、模型控制超过父限|
|M4|embedding子控制从none创建|7/2|子时间超过父限、取消被shutdown遮盖|
|M5|知识调用旧无父控制embedding重载|2/1|明确断言捕获的父控制非空失败|
|M6|boundedBy只用子deadline|5/2|过期父复活、子延长父时限|
|M7|父超时记FAILED/MODEL_ERROR|5/1|期望TIMED_OUT，实际FAILED|

mutations目录保留七份逐行patch和七份原始日志；manifest.json记录SHA、选择器、实际计数、断言类型，exitCodeObservedByRunner来自本次命令编排返回值。不是一份日志重复算七次。日志中的断言位置先失败就停止该用例，未到达的后续断言不当成已执行。

## 重放方式与边界

round2/run-service-probe.py是此次27项runner快照，../LightweightTest.java是JUnit入口；旧22项runner和首次红灯日志保留不覆盖。脚本含当时本机路径，需要相同依赖布局，并非便携构建工具。正常运行默认选六个类/count27；突变运行用`--replacement <隔离副本.java> --select <manifest中的selector> --count <found>`。应在独立副本应用对应patch，不在共享工作树上修改生产类。

Spring集成spy改动、关停恢复、数据库终态和完整六scope仍待新切片完整验证。M4证明控制传入与分类，HTTP类没有逐条参与七项突变；不能宣称所有HTTP检查点均做独立突变。当前普通聊天父预算从持久createdAt计时，队列和崩溃恢复消耗时间；显式新Run续接获得新预算，旧工具/轮数计数保留。SQL/CPU取消仍为协作式，不保证所有操作立即中止，也不能撤销已发出请求或费用。

## 后续发现的关停夹具签名

静态核对发现RunShutdownIntegrationTest的computed-result屏障仍拦截旧8参QueryLoop入口，生产RunApplicationService现在调用9参父控制入口。已同步spy签名并明确检查参数8非空；10秒、5秒等待及原持久化/恢复断言未放宽。独立javac先因遗漏hify-app/target/classes无法找到HifyApplication失败；补全只读依赖路径后编译退出0。这只是编译检查，未运行该Spring关停测试，不属于上述27项绿灯。主树c45c19a尚未包含新入口，其本轮60秒超时红灯不由这个签名差异导致，须另行诊断。
