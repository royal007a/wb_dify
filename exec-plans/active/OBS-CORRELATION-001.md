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
- 下一步：核对app窄测、补独立突变（提交捕获/清理/流回调/拒绝与取消），固定实现范围交review；再协调完整六scope门禁。研究继续覆盖其余相关主题，目标未完成。
