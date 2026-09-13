# 业务基础组件设计与交付

基线日期：2026-09-12。本轮采用“先咨询、再取舍、后实现”的方式，对业务开发前的公共能力做遗漏检查，并用 DemoItem 纵向切片验收。

## 1. 咨询结论：哪些能力应该先做

| 优先级 | 能力 | 决策与原因 |
|---|---|---|
| P0 可运行 | 版本化 DDL、Mapper 扫描、隔离线程池 | 没有它们，Mapper、数据库或异步调用无法可靠运行 |
| P1 可开发 | BaseEntity、分页、Bean Validation、时间序列化、声明式缓存 | 避免每个业务模块重复造轮子，并固定前后端契约 |
| P2 健壮性 | LLM HTTP/SSE、分类错误、provider 级熔断/分类重试、请求日志 | 在真实外部调用前统一超时、隔离、降级和观测语义 |
| 验收切片 | DemoItem CRUD | 一次验证迁移、扫描、自动填充、校验、分页、时间与逻辑删除 |

## 2. 没有照抄课程示例的地方

1. **数据库**：课程示例是 MySQL `schema.sql`；Hify 已决定 PostgreSQL 为事实源，并规定数据库变更只新增迁移，因此新增 `V2__demo_item.sql`，不再引入第二套初始化机制。
2. **Mapper 扫描**：使用 `@MapperScan(basePackages = "com.hify", annotationClass = @Mapper)`，而不是不可移植的 `com.hify.**.mapper` 字符串通配；只有显式 `@Mapper` 接口会注册。
3. **HTTP 客户端**：同步请求沿用 Spring 6 `RestClient`，流式请求使用 OkHttp EventSource。这样避免为课程示例退回 `RestTemplate`，也保留独立连接/读取/overall timeout。
4. **缓存**：Redis 默认关闭。关闭时提供 `NoOpCacheManager`，业务正确性不依赖缓存；开启后使用 `RedisCacheManager` 和按缓存名 TTL。
5. **DemoItem 边界**：建立独立 `hify-demo` 参考模块；Controller 留在 `hify-app`，业务 port/DTO、application service、Mapper、Entity 在 demo 模块，避免把业务 Entity 塞进 common 或 transport 层。

## 3. 已实现组件

- `BaseEntity`：自增 Long id、createdAt/updatedAt 自动填充、deleted 逻辑删除。
- `PageHelper`：page 默认 1，pageSize 默认 20、最大 100；IPage 到 PageResult 转换，支持 DTO 映射。
- 校验：创建/更新 DTO 使用 Jakarta Validation；全局异常返回第一个字段级错误，错误码 40000。
- 时间：LocalDateTime 固定 `yyyy-MM-dd'T'HH:mm:ss`，关闭时间戳数组输出。
- 缓存：默认 TTL 30 分钟；provider/agent 30 分钟，session 2 小时；统一 `hify:<cache>:` 前缀。
- 线程池：llmExecutor 10/50/100，asyncExecutor 5/20/200，线程名分别 `llm-` 与 `async-`。
- LLM HTTP：同步 JSON POST 5 秒连接、60 秒读取、65 秒 overall timeout；SSE 5 秒连接、120 秒读取、125 秒 call timeout；返回 EventSource 支持取消。
- 韧性：每个 provider 独立 CircuitBreaker；窗口 10、最少调用 5、失败率 50%、OPEN 30 秒、HALF_OPEN 3 次。timeout/5xx 最多 3 次且间隔 1 秒；429 为 2/4 秒退避；401/403 不重试。
- 可观测性：请求 ID、method、path、HTTP status、latency；LLM 日志只记录去 query 的目标、耗时、状态和错误类型，不记录 Authorization 或 body。

## 4. 重试边界

- 只重试尚未向客户端输出内容、且业务语义可安全重放的 provider 请求。
- 认证失败和普通 4xx 不重试。
- SSE 一旦收到/转发响应事件，不由通用组件自动重连，以免重复 token 或副作用。
- Query Loop 的 wall-clock deadline 和取消信号仍是更上层总约束；熔断重试不能把一次 Run 变成无限等待。

## 5. DemoItem 验收映射

| 请求 | 验证能力 |
|---|---|
| 空 name 创建 | Bean Validation + GlobalExceptionHandler + Result/ErrorCode |
| 正常创建 | Mapper 扫描 + Flyway 表 + BaseEntity 自动填充 |
| 分页列表 | MyBatis-Plus 分页插件 + PageHelper + PageResult + ISO 时间 |
| 更新 | application transaction + updatedAt 自动填充 |
| 删除后查询 | @TableLogic + NOT_FOUND 错误契约 |

DemoItem 是参考实现，不是产品域。真实 Provider/Agent 模块迁移到 MyBatis-Plus 时应复制其边界与测试方式，而不是复制其命名。

## 6. 验收证据

- `mvn test`：19 个普通测试通过，覆盖公共契约、HTTP/SSE 客户端、熔断/重试/总体 deadline、工具、Query Loop、工程结构和 DemoItem CRUD。
- PostgreSQL 16.15 + Testcontainers：Flyway V1/V2 成功迁移，并发幂等与终态 CAS 测试通过；H2 只承担快速反馈，不作为 PostgreSQL 兼容性证明。
- 真实进程黑盒：`./start.sh` 后，以 HTTP 请求验证空名称返回 400、创建返回 id、分页返回 ISO 时间、更新可见、删除后返回 404，数据库原始行 `deleted=1`。
- 构建交付：后端可执行 JAR、前端生产构建和根目录分发包由 `make package` 统一生成。

验收结论只覆盖本页列出的基础能力。它不代表 Provider/Agent 已完成 MyBatis-Plus 迁移，也不代表 Query Loop 已获得原生 token 流式与阻塞 I/O 强制取消。
