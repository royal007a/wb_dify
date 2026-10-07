# 两个观测切片：验收证据逐项映射与独立复核

核对源码固定为 `d913f87`（产品/测试树与受测 `05ab7a0` 相同）。本目录只补证据，
不修改产品、测试和 tasks.json。两个任务仍为 pending，不把其他任务的绿色状态
自动复制过来。这里申请按各自契约判断：同源码的完整六范围是否已经覆盖其门禁要求，
以及各自的代码/机制审查是否还缺独立结论。

2026-10-07 独立只读复核结果：OBS-READINESS-001 的代码与证据可接受；
OBS-CORRELATION-001 仍缺 runExecutor 的独立红灯/突变，不予收口。
这不改写机器状态：当前 runner 的任务身份绑定不允许把 CHAT 的 verification
直接改名或复制成 OBS 的执行记录；两个 pending 状态尚未迁移。本节记录 review
结论，后续按 Harness 规则完成各自状态迁移，不伪造重跑或放宽校验器。

## OBS-CORRELATION-001

| 任务验收条目 | 源码与反例证据 | 不能据此宣称 |
| --- | --- | --- |
| 提交时捕获；复用线程作用域 | RequestCorrelationTest：configuredLlmPoolCarriesRequestId、configuredAsyncPoolCarriesRequestId、snapshotIsCapturedAtSubmissionAndDoesNotCopySecrets、reusedWorkerDoesNotLeakContextAndCancelledFutureNeverRunsBody。原三项红灯分别是 llm 池、async 池和过滤器的断言失败，不含 runExecutor；M1 将捕获移到执行时后红。 | 没有真实供应商端到端关联审计；线程复用目前是“带 ID 后无 ID”，没有两个不同非空 ID 连续复用同一线程的专门反例。 |
| 只传播合法 requestId；恢复嵌套/异常作用域 | RequestLogContext 的白名单 Snapshot、RequestLoggingFilter 外层 requestId 恢复；测试覆盖 absent/invalid、正常/异常、嵌套直接执行及不复制秘密。M2 删除恢复后红。 | requestId 不是鉴权身份，也不是全套 OTel trace/span。 |
| 三池接入、容量和取消/拒绝不变 | ThreadPoolConfig 两池的 execute 包装；AsyncConfig 的 TaskDecorator。AsyncConfigTest 使用实际配置工厂，CommonContractsTest 检查容量、命名和饱和拒绝，取消 Future 用例要求 body 零调用。完整 gate 中旧停机恢复类亦通过。 | AsyncConfigTest 不是 HTTP→Run→LLM 的完整关联断言，且没有独立红灯/删除 TaskDecorator 的突变；取消/AbortPolicy 专项只覆盖 llm 池，没有逐池独立突变。 |
| OkHttp 回调作用域 | LlmHttpClientTest 的 streamingCallbacksCarryCapturedRequestIdWithoutSecrets、streamingFailureCallbackCarriesRequestId、malformedEventFailureCallbackGetsFreshCapturedScope；M3 和单独的 callback-scope 红绿记录。 | 回调上下文恢复不等于所有第三方线程自动继承。 |
| 先红后绿、完整显式门禁、固定范围 review | 原始实现 6a0555f、后续回调补强已进入 05ab7a0；本轮 RequestCorrelationTest 8/8、AsyncConfigTest 1/1、CommonContractsTest 5/5、LlmHttpClientTest 17/17，完整 gate 详见下文。 | 不以这四类数量代替完整门禁；review 要求补齐 runExecutor 红灯，当前不能收口。 |

旧窄测数量 27 / 28 对应当时类版本；现在 LlmHttpClientTest 包含后续截止测试，
不是重写旧结果。回调 onEvent 消费者改 MDC 后抛错的 catch 分支，重新安装同一
快照后再调 onFailure，不把消费者污染传下去。ThreadPoolConfig 仍为 AbortPolicy，
没有改 CallerRunsPolicy 或线程数量；Run 池的原关停设置也保留。

## OBS-READINESS-001

| 任务验收条目 | 源码与反例证据 | 边界 |
| --- | --- | --- |
| 正常/数据库故障/恢复、liveness 与旧 health | ReadinessIntegrationTest 使用生产 Spring/Actuator 配置、测试自己的 H2 DataSource；先正常，再注入 SQLException，最后恢复。原配置 readiness 200 使预期 503 红；加入 db 后同一测试全程通过。 | 没有关闭共享数据库；不是真实 PG 网络黑洞。 |
| 复用 db、无详情泄漏、不依赖 Redis/模型 | readiness.include=readinessState,db；MockMvc 检查响应没有 details/components/JDBC/合成异常；单独注册可选 Redis DOWN，根 health 变红而 readiness/liveness 仍绿。 | 不证明任何 JDBC 故障都能在固定毫秒内返回；不是全日志脱敏测试。 |
| 兼容与部署消费方式 | docs/OPERATIONS.md「存活与就绪」区分旧 /api/v1/health、根 health、readiness、liveness；当前部署脚本未切换，也未改重启策略。 | 无部署、更没有用 readiness 失败自动重启服务。 |
| 红绿和完整门禁、review | 6d7a2a5 只改配置和相关说明；原 2 项测试无放宽。本次 backend 中 ReadinessIntegrationTest 2/2；下述六范围全部通过。2026-10-07 mymacclaude 只读复核认可本切片。 | 只在 H2 注入 SQLException，未切换生产探针；review 未复跑测试，不自动生成新的 completed runner 记录。 |

## 当前完整门禁

固定 HEAD `05ab7a06f485a5d800009dedbbe566508bd9e775`，schema3 invocation
`d632c535-5559-4683-976b-7944ac359a99`，六 scope 实际执行，exit 0。
verification SHA `2fe9688ae24707637059abc9dff607223fd9e6bf1e13fcddcafb97d9df90266a`。
见 [完整说明](../full-gate-05ab7a0.md) 和该文链接的 run 原始日志/文本报告。
本次不是另跑两个门禁，不另计测试数量，不将相关任务状态冒充为本任务状态。

验收映射只引用确实执行的类和方法；05ab7a0 与本次文档提交之间产品和测试未改。
完整门禁只是必要证据，不替代对负例是否命中、实现是否满足契约的独立判断。

## 补交旧原始日志（不重新运行、不修改原摘要）

下列原文从此前摘要中的本地路径取回，逐一重新计算 SHA 与旧记录完全一致：

- correlation-red.log / correlation-green.log：对应 ../correlation-red.json、../correlation-green.json；
- readiness-red.log / readiness-green.log：对应 ../readiness-checks.json；
- callback-scope-red.log / callback-scope-green.log：对应 ../callback-scope-followup.json；
- m1-submission.log / m2-restoration.log / m3-stream.log / restored-green.log：对应 ../correlation-mutations.json。

复核后另外补交 `run-executor-green.log`，原位置和哈希已记在
`exec-plans/active/OBS-CORRELATION-001.md`：
SHA-256 `9c4b5f47a363bb7514b938021e059d8a2d8dee09bd7895e27c70c2620c1328b9`。
这是 2026-10-06 的 AsyncConfigTest 1/1 旧绿日志，原字节补交，**不是新跑**，
也不能替代缺少的 runExecutor 红灯。前述十份日志原样保留。

旧摘要写“只保留本地”是当时事实，未回写；现在这些字节首次补交在本目录。
这些日志不构成带签名的构建链，但不再只有不可复算的摘要哈希。原始 Maven 尾空白
保留，不做格式化。没有采集生产凭据、用户正文、服务重启或新的执行记录。

两个 OBS 最终复核通过后，应分别登记原子任务的接受范围和实际证据复用方式，
不能伪造两次 run-task 执行，也不能默改本轮验证的 taskId/runId。
