# Common 边界修复：HTTP 错误与取消传播

任务 `SPEC-COMMON-001`，修复前代码 `bf619e9`（生产代码同审计基线）。本证据只覆盖公共层，不代表全应用、PostgreSQL、浏览器或部署通过。

## 红灯与修复

新增公共 MockMvc、latch/受控 executor 回归后，旧实现27项运行：12个失败、1个错误、0跳过。日志 `/tmp/hify-common-red.log`。失败包括：缺 header/query/类型错误、405/415均被转500；406缺 JSON 错误体；预取消仍提交任务/发出 SSE 请求；排队期间取消仍执行；取消后继续重试；阻塞 POST 未释放单线程 worker（Timeout）。独立最小复现保存在 `spec-probes/`。

实现：

- 使用 ErrorCode 的40500/40600/41500和原40000；不返回被拒绝的参数值，405保留Allow，错误明确返回application/json，避免SSE-only Accept吞掉错误体。
- HTTP/重试包装在提交前、排队后、各次重试前、返回前检查同一个取消令牌和deadline；finally取消未完成Future，调用者退出时工作线程收到中断。
- 流请求预取消不建连，所有退出路径关闭EventSource；关闭/失败回调finally唤醒等待者；OkHttp关闭自动连接重试，避免未经上层授权重发POST。
- llmExecutor饱和改为AbortPolicy：CallerRunsPolicy把阻塞调用放回调用者，会使轮询取消失效。拒绝映射不可自动重试的REQUEST_FAILED，容量保持10/50/100。以受控1-worker/原队列/原拒绝策略测试饱和时不在调用者执行任务。

## 中间结果如实保留

首次修复后27项全部断言通过，但1项在MockWebServer teardown报“Gave up waiting for queue to shut down”：旧测试的5秒bodyDelay与服务端shutdown等待相撞。没有忽略异常；改用NO_RESPONSE保持socket，等待服务端真实收到请求后再取消，同时断言取消回调完成，避免用sleep猜测请求是否发出。随后28/28通过、无跳过。日志 `/tmp/hify-common-green.log`（失败）和 `/tmp/hify-common-green2.log`（通过）。饱和回归是后续新增，不包含在这28项中。

最终结果以任务runner的command.log与verification.json为准。主命令 `mvn -B -f backend/pom.xml -pl hify-common -am test`，之后执行Harness门禁。

## 边界

- 中断底层HTTP不能撤销供应商已经完成的操作；不宣称外部副作用回滚。
- HTTP公共映射使用最小Controller走真实DispatcherServlet/Advice；实际应用各路由还要跑完整矩阵。
- 未改变生产超时或放宽SSRF/TLS策略，未修改任何凭据和数据库；本轮代码尚未部署。
