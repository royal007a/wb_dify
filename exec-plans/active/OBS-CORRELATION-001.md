# OBS-CORRELATION-001

## 原因

来源为jikesummary C133《可观测性与排错》PDF第2页跨线程MDC机制，C168第2-4页改造前测试与C169第1-3页确定性框架测试。基线d2b39c2：RequestLoggingFilter只在HTTP线程设置requestId，runExecutor/llmExecutor/asyncExecutor未传播；finally的remove还会清除外层已有requestId。

## 范围

只补关联ID作用域，不引入OTel/exporter/计费，不复制全部MDC，也不把用户传来的ID当作身份凭据。仅允许既有 `[A-Za-z0-9._-]{1,64}` requestId；没有合法ID的后台任务维持无ID，不借用线程的旧请求身份。worker执行结束必须恢复原MDC（包括异常），以支持嵌套/内联执行且防线程复用串号。

JDK线程池保留ThreadPoolExecutor类型、队列大小、AbortPolicy及destroyMethod；通过execute装饰覆盖submit/FutureTask。Spring runExecutor使用TaskDecorator，保持shutdown现有设置。避免CallerRunsPolicy和装饰器吞异常，不改业务控制流。OkHttp SSE回调不经过这三个池，因此在stream注册时另捕获同一只读快照，逐回调安装与恢复，不能只修线程池就宣称完整流链关联。

## 验证

先在未改生产代码上跑测试：真实配置的线程池收到null而非父requestId，过滤器结束丢失外层requestId。再实现和补齐：提交后修改父值、连续无ID任务、异常后下一任务、仅白名单传播、非法ID、被拒绝任务不改调用方、runExecutor实际装配。原CommonContractsTest和停机/取消测试不能删改放宽。

窄测仅证明相应机制，不代表整条模型SSE链或生产已通过。完整门禁要求六scope，固定提交交review，不和hify-cc重测试同时启动。部署不在本切片内。

## 恢复检查点

- 复现提交948a494：3项纯断言红灯，日志在`/tmp/hify-course-20261006.hhEhwt/correlation-red.log`，脱敏摘要已提交。
- 当前实现：白名单Snapshot + 三个生产线程池 + 4种OkHttp回调作用域。common窄测27/27通过，摘要`docs/research/jikesummary-20261006/correlation-green.json`。
- app线程池配置窄测已通过：AsyncConfigTest 1/1，失败/错误/跳过均为0；日志`/tmp/hify-course-20261006.hhEhwt/run-executor-green.log`，SHA-256 `9c4b5f47a363bb7514b938021e059d8a2d8dee09bd7895e27c70c2620c1328b9`。这是直接实例化生产配置的测试，不是完整Spring/Run端到端验收。
- 固定实现948a494..b3f55df已交mymacclaude只读review。隔离archive的3项独立突变（提交捕获、恢复、SSE回调）均为断言失败，恢复后逐字比对源文件并27/27通过；见`correlation-mutations.json`。拒绝/取消目前只有回归而非独立突变，不能扩大这条证据口径。
- 下一步：处理review，协调完整六scope门禁。研究继续覆盖其余相关主题，目标未完成。
- 自查补强：onEvent消费者改MDC再抛错，会把改坏的上下文传给catch分支onFailure。新增用例先红后绿，修为单独重装快照；common目前28/28，见`callback-scope-followup.json`，旧27项结果保留不回写。
- 验证环境：本次已启动专用`hify-verify-20261004`，当前无运行容器，默认Docker context仍为`colima`；后续门禁必须显式`DOCKER_CONTEXT=colima-hify-verify-20261004`。尚未启动完整门禁，不要把VM运行误报为验收进行中。

## 2026-10-07 独立复核后的缺项

以上是各历史阶段记录。05ab7a0 的完整六范围已经通过，专用 VM 已停止；
但本切片还不能收口：原三项断言红灯只覆盖 llm 池、async 池和过滤器，
不含 runExecutor。AsyncConfigTest 的旧绿日志已原样补交到
`docs/research/jikesummary-20261006/observability-closeout/run-executor-green.log`，
仍须在隔离副本删除 setTaskDecorator、确认该用例红在 requestId 断言，再恢复
同一份源码复跑绿；不能把编译错误或空指针算成命中。尚未运行这项突变。

复核时缺少两个不同非空 ID 连续复用同一 worker 的专项场景。现已在既有
reusedWorker 用例中补第二个非空 ID，并断言 Thread 对象相同、第二次完整
MDC 只有第二个 requestId；原无 ID、取消 body 零调用断言保留。当前仅写好，
尚未执行，不能引用旧 8/8 作为新断言通过；未做这组新断言的专属突变。
取消和 AbortPolicy 专项仍只在 llm 池测过。CC 持有测试窗口时不启动重测试。

## 2026-10-07 16:45 窄验证补齐（待复核）

前节“尚未执行”是历史阶段状态。隔离副本删除 runExecutor 的 TaskDecorator，
AsyncConfigTest 在 requestId map 断言处失败（1 failure、0 error），恢复后
AsyncConfigTest 1/1、含两个非空 ID 同线程断言的 RequestCorrelationTest 8/8 通过。
原始红绿日志、文本报告及哈希见
`docs/research/jikesummary-20261006/run-executor-mutation-d68bf22/`。
这次没有重跑六范围、没有新增产品改动；新线程复用断言没有专属突变。
窗口已交还 CC，机器任务保持 pending，等待独立复核和合规状态迁移。
