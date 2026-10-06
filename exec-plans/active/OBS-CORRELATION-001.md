# OBS-CORRELATION-001

## 原因

来源为jikesummary C133《可观测性与排错》PDF第2页跨线程MDC机制，C168第2-4页改造前测试与C169第1-3页确定性框架测试。基线d2b39c2：RequestLoggingFilter只在HTTP线程设置requestId，runExecutor/llmExecutor/asyncExecutor未传播；finally的remove还会清除外层已有requestId。

## 范围

只补关联ID作用域，不引入OTel/exporter/计费，不复制全部MDC，也不把用户传来的ID当作身份凭据。仅允许既有 `[A-Za-z0-9._-]{1,64}` requestId；没有合法ID的后台任务维持无ID，不借用线程的旧请求身份。worker执行结束必须恢复原MDC（包括异常），以支持嵌套/内联执行且防线程复用串号。

JDK线程池保留ThreadPoolExecutor类型、队列大小、AbortPolicy及destroyMethod；通过execute装饰覆盖submit/FutureTask。Spring runExecutor使用TaskDecorator，保持shutdown现有设置。避免CallerRunsPolicy和装饰器吞异常，不改业务控制流。

## 验证

先在未改生产代码上跑测试：真实配置的线程池收到null而非父requestId，过滤器结束丢失外层requestId。再实现和补齐：提交后修改父值、连续无ID任务、异常后下一任务、仅白名单传播、非法ID、被拒绝任务不改调用方、runExecutor实际装配。原CommonContractsTest和停机/取消测试不能删改放宽。

窄测仅证明相应机制，不代表整条模型SSE链或生产已通过。完整门禁要求六scope，固定提交交review，不和hify-cc重测试同时启动。部署不在本切片内。
