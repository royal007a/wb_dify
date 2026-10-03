# Common HTTP / 取消原子修复

前置证据：`docs/evidence/spec-probes/README.md`。不扩新功能、不改数据库、不部署。

1. standalone MockMvc 覆盖缺必填header/参数、类型错误、405/415/406、敏感值不回显。
2. latch/计数 executor 覆盖预取消不提交、排队后取消不执行、worker 取消后中断、重试前检查取消/截止。
3. 先保留旧实现红灯，再仅修改 ErrorCode、GlobalExceptionHandler、LlmHttpClient、CircuitBreakerService；生产预算不调大。
4. 每次调用在执行前和最终返回前复查控制令牌，finally 取消未完成 Future。流式调用预检、取消清理；禁用 OkHttp 自动 POST 重发。
5. `mvn -B -f backend/pom.xml -pl hify-common -am test` 是 runner 的实际命令，随后验证 harness；独立 common 通过不意味着全应用通过，原 SPEC-AUDIT-001 完整失败基线仍需修复/重跑。

取消约束补充：llmExecutor 的 CallerRunsPolicy 会在饱和时把阻塞 HTTP 放到轮询取消的调用者上，故改为 AbortPolicy；拒绝映射为不可自动重试的 REQUEST_FAILED，不增加线程/队列容量。这是取消门禁所需的范围内变更，并以饱和测试验证。
