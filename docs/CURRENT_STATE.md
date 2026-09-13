# Hify 当前实现边界

基线：2026-09-13 本地 `/Users/weberzhao/hify`，Git `main` 分支；Maven 3.9.16 已安装。初版已经完成单元/集成测试、Vue 生产构建、PostgreSQL/Flyway 容器启动和端到端 HTTP/SSE 冒烟验证。详细命令与结果见 `INITIAL_RELEASE.md`。

## 已实现并验证的初版能力

| 能力 | 代码证据 | 当前边界 |
|---|---|---|
| Spring Boot 入口 | `HifyApplication.java`、`HealthController.java` | Spring MVC/Tomcat 已运行验证；`GET /api/v1/health` 返回统一 Result 和 HTTP 200 |
| Provider CRUD | `ProviderController`、`ModelProviderRepository` | 只有 list/create；API `/api/providers`，直接返回 Entity |
| Agent CRUD | `AgentController`、`AgentDefinitionRepository` | 只有 list/create；无 draft/version/publish |
| Conversation/Message | `hify-app/RunController`、`hify-chat` repositories | 可创建会话、追加消息；无用户、分页和版本绑定 |
| Run/Event | `hify-chat` 的 `AgentRun`、`RunEvent`、`RunApplicationService` | 并发幂等、异步执行、终态 CAS、持久化取消、事件持久化和 SSE replay 已验证 |
| Query Loop | `runtime/QueryLoop.java`、`runtime/plan` | 结构化 tool-call 循环、Plan/Step/Attempt/Checkpoint/ReplanDecision、确定性 read-only Replan、maxTurns/replan/deadline/工具/token 预算和明确终态 |
| Checkpoint/恢复 | `RunCheckpoint`、`V3__run_replan_control.sql` | 保存已配对消息、turn/tool 计数与完整 plan 快照；恢复前后 plan id/version/digest 一致；启动时恢复 RUNNING read-only Run，取消中的 Run 收敛为 CANCELLED |
| Intent Router | `hify-chat/com.hify.intent`、`IntentRoutingController` | 四出口契约、确定性规则层、结构化模型候选、低置信/歧义/缺槽澄清、120 条中文评测集；当前仅预览，不接管 Run |
| Mock 模型 | `MockModelClient.java` | 可触发时间或单个二元运算工具 |
| OpenAI-compatible | `OpenAiCompatibleModelClient.java` | 能解析原生 `tool_calls` 并保留 call id；Spring RestClient 同步调用，不流式 |
| 内置工具 | `ToolRuntime.java` | current_time/calculator；schema 必填/类型校验、read 权限和 32 KiB output cap |
| 持久化 | `V1__baseline.sql`、JPA Entity | Flyway + PostgreSQL 16.15 部署验证；H2 只用于本地/测试便利 |
| Console | `frontend/` | Vue 3 + TypeScript + Vite + Element Plus；浅色设计系统、响应式深色导航、统一 axios/通知、公共表格/表单组件、Provider mock 验收页和 Query Loop Playground 已构建/渲染验证 |
| 启停与部署 | `start.sh`、`stop.sh`、`Makefile`、`deploy/up.sh`、`compose.yaml` | 开发态入口 `http://localhost:5173`，容器入口 `http://localhost:8088`；PID、日志、健康轮询和失败回收已验证 |
| 后端工程 | `backend/pom.xml`、10 个子模块 | Maven reactor、统一 Result/异常、MyBatis-Plus/Redis 配置、业务模块空壳和 DemoItem 参考切片已构建验证 |
| 业务基础组件 | `hify-common`、`hify-demo`、`V2__demo_item.sql` | BaseEntity、分页、校验、ISO 时间、可选 Redis Cache、隔离线程池、LLM HTTP/SSE、provider 级熔断/分类重试和请求日志均有测试或运行证据 |

## 尚未实现

- 模型 token 原生流式输出；当前 SSE 投影 Run/模型/工具事件，并在模型完成后发送整段 `message.delta`。
- 原生模型流式调用的取消传播；同步 Provider HTTP 已接入共享 cancellation/deadline token，并以短轮询中断阻塞 Future。
- 精确 token/cost 计量；当前 token budget 是字符数估算，尚无价格表与成本预算。
- RunStep/ToolCall 独立表、通用完整 JSON Schema、工具超时与写工具交互式批准；当前 Attempt/Plan 通过事件追踪，checkpoint 持久化 call/result 配对消息。
- 高风险 write/external/execute 工具策略；当前只有 read 工具，未绑定工具会被拒绝。
- 完整审计字段；并发幂等数据库冲突归一和终态数据库 CAS 已完成。
- MCP、RAG、Workflow、文档对象存储与 Redis；`compose.yaml` 已提供，但本机缺少 Compose plugin，实际部署由等价 `deploy/up.sh` 完成。
- 认证/用户、安全出口、SSRF 防护、审计日志、CI 和生产级密钥管理。
- JPA 到 MyBatis-Plus 的 Repository 迁移；当前 MyBatis-Plus 地基已配置，已验证的持久化实现仍保留 JPA，禁止一次性重写。
- Intent Router 的真实 Provider 离线评测、shadow 事件和主链路 dispatch；当前 rule-only v2 Top1 为 82.50%（unknown recall 100%），模型层已有契约与单测但尚无真实成本/延迟数据。

## 现状与目标架构的冲突

1. 旧同步 `/api/chat` 和长事务 `ChatService` 已删除；所有对话执行统一进入异步 Run API。
2. Controller 直接访问 Repository 并返回 JPA Entity，违反目标的 transport/application/domain/infrastructure 边界，也可能暴露敏感字段。
3. QueryLoop 已把剩余 deadline/cancellation token 传入同步 Provider HTTP；底层 socket read timeout 仍是上限值，但等待线程会按 token 提前中断。原生 SSE 模型流尚未接入同一控制对象。
4. `ToolDefinition.risk` 已执行 read-only policy，并具备 Try/Replan 状态机；还没有完整 write policy、精确确认 token、side-effect ledger、工具级超时和补偿动作。
5. Run 终态已使用 `state + version` 单 SQL compare-and-set；多副本事件序号仍需进一步设计。
6. PostgreSQL/Flyway 已验证，但 pgvector、JSONB、HNSW 和生产恢复尚未进入本初版。
7. WebFlux 已移除并对齐 Spring MVC/SseEmitter；Provider 仍是同步非流式适配器。

## 处理原则

- 不删除原型后重写；先用 characterization tests 固定 mock provider、tool call/result 和会话行为。
- 当前初版优先建立纵向闭环；后续仍按 `PHASE_0_ALIGNMENT.md` 完成多模块和剩余契约，再扩展业务能力。
- README/设计文档分别使用“源码存在”“测试通过”“运行验证”三种证据等级，禁止统称“已完成”。
