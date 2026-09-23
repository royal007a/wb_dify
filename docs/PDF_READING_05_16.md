# 《Claude Code 企业级全链路开发实战》05–16 讲阅读、映射与实现核验

阅读范围：`/Users/weberzhao/Downloads/Claude Code 企业级全链路开发实战/05 - *.pdf` 至 `16 - *.pdf`，共 12 讲、129 页。正文通过 `pdftotext -layout` 抽取，并与 Hify 当前源码、测试、迁移和运行脚本逐项对照。本文区分课程主张、Hify 的有意识偏离、源码证据和仍需补齐的边界，不把“文件存在”当作“能力已验收”。

## 一、12 讲主线

### 05｜你是架构师，Claude Code 是工程团队

- 核心认知：AI 不是替人做产品和架构决策的黑盒，而是按约束执行的工程团队；人的工作从逐行编码转向范围、架构、标准、取舍和验收。
- 三层分工：人必须拍板（产品边界、架构、跨模块一致性）；AI 实现、人验收（业务代码、接口、测试、文档）；AI 全权处理（格式化、样板、脚本、Makefile）。
- 三步验收：先查意图是否做对，再查质量/规范/一致性，最后查边界、并发、异常和安全；不能因为连续几次输出正常就取消机制化测试。
- Hify 落地：根 `AGENTS.md`、`docs/SPEC.md`、ADR、Harness 任务和 `harness/verify.sh` 共同把“指挥—执行—证据验收”固化。高风险部署、迁移和凭证操作仍需授权。

### 06｜产品定义：第一行代码前先想清楚

- 先看 Dify 全貌，再用三问裁剪：没有它产品是否成立；做到什么程度够用；能否一句话说清产品。
- Hify 产品句：面向 20–50 人内部团队、可本地部署的 AI Agent 平台，覆盖 Provider、Agent、对话、工具、知识库、简版 Workflow 和管理台。
- 核心保留：Provider、Agent、对话、工具、管理台；知识库和 Workflow 降级为可交付的最小纵向切片；多租户、计费、插件市场、复杂 RBAC、可视化工作流等明确不做或后置。
- 课程技术栈是 Spring Boot + MySQL + Redis；Hify 选择 Spring Boot + MyBatis-Plus + PostgreSQL/pgvector + 可选 Redis，原因是团队熟悉、单栈维护成本低、减少双数据库事实源。
- 运维假设：峰值约 3–5 QPS，真正瓶颈是 3–30 秒 LLM 长连接和 SSE，而非普通 QPS；因此先做单实例、连接/线程隔离和可观测触发器，不提前上微服务/K8s。

### 07｜架构设计（上）：应用架构与代码组织

- 采用模块化单体：`hify-app`、`common`、`provider`、`agent`、`chat`、`mcp`、`knowledge`、`workflow`；部署简单，代码边界为未来拆分留口子。
- 依赖单向：Chat 可消费 Agent/Provider/Knowledge/Workflow/MCP 契约，业务模块不得反向依赖 Chat；跨模块只能走公开 Service/Port，不得直接引用对方 Mapper/Entity。
- 分层职责：Controller 处理协议和校验，Service/Application 编排用例和事务，Mapper 只做持久化，Entity 对应表，DTO 控制外部契约和敏感字段暴露。
- LLM 外调必须隔离：独立线程池、连接/读取/整体超时、熔断、分类重试和取消；认证失败不重试，网络抖动可有限重试，流式已经发出后不能整体重放。
- Hify 进一步拆出 api/application/domain/infrastructure，是为把事务、预算、取消传播与 Run 状态机/权限规则分离，不是为了堆 DDD 空壳。

### 08｜架构设计（下）：部署、性能和数据

- 初期部署：Nginx → 单 Hify App → PostgreSQL，Redis 作为可选加速/事件层；数据、日志、上传内容必须持久化到 volume。
- SSE 关键点：关闭 Nginx buffering，设置 idle/overall 上限，避免普通请求线程被模型调用长期占满；扩容由连接数、排队、数据库规模、SLO 和发布冲突触发。
- 数据设计先确定领域关系和事实 owner，再随模块实现字段；逻辑删除字段参与索引，组合索引等值列在前、范围列在后，多对多关系双向建索引，唯一性由数据库约束兜底。
- 课程的 MySQL/自增 BIGINT/禁止 NULL 是示例，不是 Hify 无条件规范；Hify 对外使用 UUIDv7/ULID，必填字段 NOT NULL，未知语义保留 NULL，理由见 `docs/PDF_READING_SUMMARY.md` 差异表。
- Hify 证据：`compose.yaml`、Nginx 配置、Flyway V1–V19、PostgreSQL 并发测试、`deploy/up.sh`/`down.sh`。

### 09｜CLAUDE.md、Skills 与工程规范

- 根规则应是短地图和硬约束，详细架构/API/数据模型放 `docs/`；目录级规则只在确有差异时增加。
- 规范粒度跟着真实跑偏长出来：把反复出错的命名、异常、依赖、数据库和安全边界写成规则，不要预先编百科全书。
- Skill 只沉淀重复且稳定的流程，例如 CRUD、迁移、Provider 适配、测试/部署；一次性想法留在计划或 ADR。
- SDD 闭环：AI 执行 → 人/测试发现偏差 → 修正规范 → 下一轮不再重复；规范必须能追溯到架构决策、缺陷或验收标准。
- Hify 已将该方法扩展为 `AGENTS.md` + `docs/harness` + `exec-plans` + `tasks.json` + `verify.sh`；`progress.md` 只能生成，不能手改。

### 10｜顶层设计全流程演示

- 演示顺序是：产品全景/裁剪 → 应用架构 → 部署与数据 → 规则文件 → 分步执行与验收；每个阶段先咨询、再判断、后执行。
- 关键操作不是把一条超长提示词丢给 AI，而是先形成可审查的决策材料，再把任务拆到一次可 review 的粒度，并在依赖完成后进入下一步。
- 复用完整错误上下文，不只贴最后一行；compact/clear 用于控制上下文，不替代外部状态、任务和证据。
- Hify 的 Harness 是该讲方法论的机器化实现：一个原子任务、一条基线 commit、一份 checkpoint、一组验证证据，支持跨会话恢复。

### 11｜工程初始化（上）：后端骨架与公共基础设施

- 初始化必须按依赖拆四步：Maven 多模块骨架 → `hify-common` → 业务模块空壳 → 启动/健康检查验收。
- 公共基础设施包括统一 `Result/PageResult`、`ErrorCode/BizException/GlobalExceptionHandler`、MyBatis-Plus 分页/自动填充/逻辑删除、RedisTemplate 序列化和工具类。
- 业务模块只创建包和占位类，不在骨架阶段顺手生成业务代码；版本号集中在父 POM dependencyManagement。
- Hify 证据：10 个 Maven 模块、`hify-common` 基础设施、DemoItem 参考切片、统一健康接口和模块结构测试。

### 12｜工程初始化（下）：前端工程与一键启动

- 前端按依赖拆为：Vue 3 + TypeScript + Vite 骨架/代理 → Axios 统一请求层 → Router/页面空壳 → 后端联通 → 启停脚本/Makefile。
- `request.ts` 统一解包 `Result.data`、处理错误提示；Vite `/api` 代理开发态转发 8080，避免业务页面重复处理响应壳和跨域。
- `start.sh` 负责依赖检查、构建、健康轮询和失败回收；`stop.sh` 按 PID 优雅停止；Makefile 提供 start/stop/restart/build/clean/package。
- Hify 已进一步补齐 Vue Query/Pinia 状态边界、Provider/Agent/Knowledge/Workflow/MCP 管理台和 Chat Playground；生产入口是 5173，容器入口 8088。

### 13｜基础组件（上）：后端业务基础设施

- 先咨询清单再执行，按三档排序：必须先做（schema.sql、MapperScan、线程池）；业务前补（BaseEntity、PageHelper、校验、时间序列化、Spring Cache）；健壮性补齐（HTTP Client、熔断重试、结构化日志）。
- DemoItem 纵向切片验证基础设施协同：校验 → 统一异常；创建 → 自动填充；分页 → PageHelper/插件；时间 → ISO；删除 → 逻辑删除。
- LLM HTTP 请求要区分普通和流式连接池、connect/read/idle/overall timeout，并按 TIMEOUT/AUTH_FAILED/RATE_LIMITED 分类；Provider 级熔断避免持续等待坏供应商。
- Hify 证据：`BaseEntity`、`PageHelper`、`ThreadPoolConfig`、`LlmHttpClient`、`CircuitBreakerService`、`JacksonConfig` 和对应单测。

### 14｜基础组件（下）：前端 UI 与公共组件

- 先定产品/用户/风格/锚点，再由 AI 生成 CSS 变量；Hify 采用浅色内容区 + 深色侧边栏 + 蓝紫主色 + 青色状态，参考 Linear/Supabase。
- 公共组件解决重复脏活：`HifyTable`（分页/loading/refresh）、`HifyFormDialog`（新增/编辑/校验）、`useConfirm`、`useRequest`、统一通知。
- UI review 关注可观察判断：间距、行高、状态语义、弹窗宽度、响应式隐藏列、按钮层级；不是逐个 CSS 色值审查。
- Hify 证据：`tokens.css`、`App.vue`、公共组件、Provider/Agent/Knowledge/Workflow/MCP 页面和 Playwright/构建验证。

### 15｜实操课：工程搭建全流程

- 把前 11–14 讲串成可执行剧本：先确认规则 → 生成一小步 → 编译/运行 → 记录错误 → 带完整日志修复 → 再验收。
- 任务拆分标准：生成量超出一次 review 范围，或步骤有依赖关系，满足任一就拆；先地基、后框架、最后交付验收。
- 前端调整用“具体视觉判断 → 小批量修改 → 浏览器复看”循环，不要求一次生成完美 UI。
- Hify 的 `run-task.sh`、`verify.sh`、证据目录和 progress 生成器将这套流程固化；当前尚缺的是把本轮课程阅读纳入机器任务和新鲜的本地运行证据。

### 16｜模型提供商管理：第一个完整交付闭环

- 标准流程：咨询供应商/协议与数据模型 → Entity/Mapper → DTO → Service → Controller → 前端 API/页面 → 浏览器验收。
- 一期协议：OpenAI、Anthropic、Gemini 原生 + OpenAI-compatible 通用；不引入 Spring AI/LangChain4j 等大框架，采用小型 Adapter Registry + `LlmHttpClient`。
- 鉴权用结构化配置/credentialRef；模型分展示名与调用 ID；ProviderHealth 独立表承载高频状态、延迟、失败计数和最近错误，避免和配置缓存争锁。
- 课程示例用简单 if/else 先跑通；Hify 已演进为四类协议 Adapter、流式 tool call 映射、出站 SSRF/redirect 边界、credentialRef 不回传和安全测试。
- 交付验收必须覆盖：四类型校验、CRUD/分页/逻辑删除、模型约束、连通性测试、健康更新、缓存驱逐、原生协议映射和浏览器真实 CRUD；不能只以 curl 或源码存在宣称完成。

## 二、课程要求与 Hify 现状对照

| 课程能力 | Hify 证据 | 状态 |
|---|---|---|
| 产品裁剪与边界 | `SPEC.md`、`ARCHITECTURE.md`、`CURRENT_STATE.md` | 已制度化 |
| 模块化后端骨架 | `backend/pom.xml`、MavenStructureTest | 已验证 |
| 公共基础设施 | `hify-common`、CommonContracts/LlmHttp/CircuitBreaker 测试 | 已验证 |
| 前端壳、设计系统、公共组件 | `frontend/src`、npm typecheck/build、管理台页面 | 已验证 |
| Provider 完整闭环 | `hify-provider`、V5/V6/V7、ProviderApiIntegrationTest、原生流式测试 | 已验证；课程基础版已超越 |
| Agent 草稿/发布 | `hify-agent`、V6–V8、Agent API/缓存/迁移测试 | 已验证 |
| Chat 流式与运行状态 | `QueryLoop`、Run/Event、SSE replay/取消、RunFlowIntegrationTest | 已验证；真实故障矩阵仍需持续扩充 |
| Knowledge/RAG | `hify-knowledge`、V14/V15、KnowledgeApiIntegrationTest | 独立纵向切片已验证；已绑定 Agent/Chat |
| Workflow | `hify-workflow`、V16、WorkflowApiIntegrationTest、Canvas | 确定性运行时与画布已验证；非课程后续完整画布/复杂节点范围 |
| MCP | `hify-mcp`、V17/V19、MCP 测试 | READ 能力、快照、绑定和恢复已验证；write/完整 conformance 后置 |
| Harness/持续验收 | `harness/*`、`exec-plans/*`、`docs/harness/*` | 已建立；本轮新增课程阅读任务与本地证据 |

## 三、仍需按课程精神持续补齐

1. 课程 05 的“人验收 AI”不能被代码量掩盖：关键 Provider/Run/安全边界继续由测试和独立 review 复核。
2. 课程 08/13 的精确 token/cost、备份恢复、容量测试和流式故障矩阵仍是生产门槛，不能因 happy path 通过而关闭。
3. 课程 16 的 write 工具安全契约（planDigest、side-effect ledger、幂等、compensation）尚未完成，当前只开放 READ。
4. README 与部分早期历史文档仍保留“尚未完成 Provider/Agent/MCP/RAG/Workflow”的旧描述，应以后续 `CURRENT_STATE.md`、Harness 证据和本文件为准，并逐步清理陈旧表述。
5. 本轮验收顺序：先 `harness`/`backend`/`frontend`/`migration`/`runtime`/`eval` 验证，再本地启动和浏览器 smoke；失败要记录为证据，不用“代码看起来完整”替代运行证据。

## 四、可复用交付模板

```text
咨询模式：列出领域选型、约束、替代方案和边界
→ 决策记录：写入 SPEC/ADR/接口/数据模型
→ 原子任务：按 Entity → DTO → Service → Controller → UI 拆分
→ 每步验证：编译、单测、集成测试或浏览器 smoke
→ 证据归档：commit、命令、日志、运行版本、遗留风险
→ 独立 review：先意图，再质量，最后边界
→ 只有全部 Gate 通过才标记 completed
```
