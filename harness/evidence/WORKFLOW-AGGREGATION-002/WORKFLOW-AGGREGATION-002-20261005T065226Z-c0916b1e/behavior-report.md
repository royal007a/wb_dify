# 方法级行为与接口映射

代码：`5010e0566c1aa25df93d4ac7b2c0224079b08e60`。只有具名子场景给pass/fail/not-run，不给整路由或F组打全通过。

完整fixture、命令、实际参数化用例状态及残余边界见同目录behavior-report.json。

| 子场景 | 状态 | 断言范围 |
|---|---|---|
| RESUME-SOURCE | pass | 缺失/跨会话来源相同40000固定正文，无ID回显、零Run/消息/事件/调度增量；正常创建计数增加 |
| INDEX-DIALECT | pass | 事务内单次借还连接，错误不冒充H2成功；FAILED持久化、原分块不变，正常PG向量非空 |
| WORKFLOW-PINNED-DIGEST | pass | 固定摘要不符时WORKFLOW_ERROR，无新增workflow/node/assistant；同一版本仅加载一次，仍检查原始DSL |
| NUL-READ-KB | pass | query中间/前后NUL与service KB id拒绝为400/PARAM_ERROR；计数不变，无模型工厂调用 |
| NUL-READ-FROZEN | pass | revision/snapshot查询与身份字段NUL在读库前拒绝，持久计数不变 |
| NUL-READ-MEMORY | pass | query/entity和service runId NUL在trim前拒绝，不写Run/消息/索引，不调用模型工厂 |
| NUL-READ-INTENT | pass | agentId/input NUL400且无模型工厂调用，HTTP/service一致，无持久写入 |
| NUL-BIND-KB | pass | KB绑定目标NUL400，直接service同样拒绝；完整Agent和绑定不变；合法替换成功 |
| NUL-BIND-MCP | pass | serverId/toolNames NUL400，直接service同样拒绝；原绑定不变；合法替换成功 |
| NUL-BIND-WORKFLOW | pass | workflowId NUL400，直接service同样拒绝；完整Agent和原绑定不变；合法替换成功 |
| NUL-BIND-TOOLS | pass | toolIds NUL400且不回显输入；直接service拒绝；原绑定不变；合法替换成功 |
| NUL-ENABLED-TOOLS | pass | enabledTools含NUL时HTTP/service400，无Agent/绑定行新增；合法绑定保存 |
| NUL-DEEP-CONFIG | pass | 深层NUL坏创建/更新400且图不变；合法深层配置保存并回读 |
| NUL-UPLOAD | pass | 上传正文/文件名/mediaType拒绝，零文档/任务；合法UTF8原文保存 |
| NUL-KB | pass | KB名称/描述创建更新400，HTTP与service均拒绝，回读不变 |
| NUL-AGENT | pass | Agent管理文本创建更新400，合法更新通过，坏请求零改动 |
| NUL-WORKFLOW | pass | Workflow名称/描述/节点/边/配置键值400，原图不变 |
| NUL-PROVIDER | pass | Provider/Model/auth文本400，不改模型目录，合法更新通过 |
| NUL-MCP | pass | MCP管理输入400，校验先于保存/凭据处理 |
| NUL-CONVERSATION | pass | 会话标题NUL400且零会话写入，字面量反斜杠u0000保留 |
| NUL-WORKFLOW-RUN | pass | 直接Workflow试跑input400且零运行行，合法试跑SUCCEEDED |
| BIND-KB | pass | HTTP绑定/发布保留corpusVersionId/manifest，Chat经过知识检索入口 |
| MCP-READBACK | pass | 目录先空后两条且与发现结果相同；归档后列表消失、详情/工具404 |
| RUN | pass | health200/code200；Run202、同key200、异体40901；工具/终态事件和持久消息 |
| PROVIDER | pass | CRUD回读、displayName/modelId分离、鉴权不入配置值、独立健康、删除404、非法模型400 |
| NATIVE | pass | 三种连接测试success且请求鉴权头正确 |
| CREDENTIAL | pass | 保留名/别名与未授权目的地拒绝；旧记录调用不发送凭据 |
| HTTPCLIENT | pass | 401/429/5xx分类、断流失败、阻塞取消、拒重定向 |
| AGENT | pass | 草稿CRUD、参数和工具校验、发布快照、旧/新会话版本、批量列表查询 |
| ARCHIVE | pass | 归档草稿/解绑且保留旧快照，归档名称不复用 |
| CATALOG | pass | 新旧工具目录精确字段/数量，旧会话id/title/messages回读 |
| DEMO | pass | CRUD、分页校验、时间字段、逻辑删除 |
| IDENTITY | pass | 停用后同key恢复、异体含resume40901、新key非法resume40000、零重复工作；20000边界/超限400、删除404、无关约束500 |
| LOOKUP | pass | 200/404/400、no-store、零写入/调度、会话隔离 |
| CANCEL | pass | 重复/终态取消202且不改终态；事件不重复、取消前无模型工具工作 |
| SSE | pass | 慢客户端不占提交/连接；外部游标400；满额50300且不排队；释放订阅 |
| ADMISSION | pass | 202 FAILED/EXECUTOR_REJECTED，同key200；SSE重放；取消优先；后续行继续 |
| GATE | pass | 开放缺口和未验证必需证据阻止FINISH，search不是canonical证据 |
| REPLAN | pass | LOCAL_REPLAN/RETRY/CLARIFY/INTERRUPT正确区分并保留证据 |
| HISTORY | pass | 摘要/前缀校验，旧工具拒绝重执行，值/类型/数组变化冲突 |
| SHUTDOWN | pass | 关闭≠取消，重启前留interrupted，真实取消原因不被重启补写，计算完结果可提交 |
| LEASE | pass | 副作用前执行资格复查、取消拒绝、能力摘要确定 |
| CONTEXT | pass | 独立预算、先归档后压缩、仍超限拒绝 |
| MEMORY | pass | 来源摘要、冲突/摘要错误、search→detail原文回读与导航/验证区分 |
| MEMORY-GATE | pass | memory/catalog/search/detail隔离，RUNNING阶段不漏正文，canonical历史仍在 |
| CHILD | pass | 父提交成功后消费、缺执行者收敛lost并记录 |
| INTENT | pass | 确定性决策且模型零调用，空输入400 |
| TIME | pass | 问候不调用工具，重复问时间与计算每轮使用新调用 |
| KB | pass | CRUD回读、上传索引/分块/检索、冻结归档回读、停用和非法文件/策略拒绝 |
| UPLOAD | pass | 2MiB接受，直连精确10MiB接受，10MiB+1/13MiB安全JSON41300 |
| KNOWLEDGE-GATE | pass | 空库模型零调用，伪引用澄清，恢复K映射且不重检索，来源回读 |
| WORKFLOW | pass | CRUD/校验/发布/版本/运行详情、两分支、旧版本稳定、非法必经变量拒绝、归档保留版本 |
| PINNED | pass | 解绑后旧会话仍运行旧版本/checksum，取消阻止后续节点 |
| MCP | pass | 发现revision、READ调用/schema校验/写工具拒绝、改连接需重发现、旧版本保留schema |
| MCP-TOKEN | pass | 加密只写、KEEP/替换/清除、旧快照旧凭据、回显脱敏、未批准旧引用零请求 |
| ERRORS | pass | 400/404/405/415精确JSON码、SSE Accept负例、multipart优先级 |
| EVAL | pass | 固定数据质量/安全断言与可复跑指标 |

## 68条接口

映射仅表示哪些具名方法提供了部分行为证据，不意味着错误码或状态组合穷举。

| 接口 | 子场景 | 映射状态 |
|---|---|---|
| `GET /api/conversations/{id}` | CATALOG | mapped-subcases-only |
| `GET /api/tools` | CATALOG | mapped-subcases-only |
| `GET /api/v1/agents` | AGENT | mapped-subcases-only |
| `POST /api/v1/agents` | AGENT, NUL-AGENT, NUL-ENABLED-TOOLS | mapped-subcases-only |
| `DELETE /api/v1/agents/{agentId}` | ARCHIVE | mapped-subcases-only |
| `GET /api/v1/agents/{agentId}` | AGENT | mapped-subcases-only |
| `PUT /api/v1/agents/{agentId}` | AGENT, NUL-AGENT | mapped-subcases-only |
| `PUT /api/v1/agents/{agentId}/knowledge-bindings` | BIND-KB, NUL-BIND-KB | mapped-subcases-only |
| `PUT /api/v1/agents/{agentId}/mcp-bindings` | MCP, NUL-BIND-MCP | mapped-subcases-only |
| `POST /api/v1/agents/{agentId}/publications` | AGENT | mapped-subcases-only |
| `PUT /api/v1/agents/{agentId}/tools` | AGENT, NUL-BIND-TOOLS | mapped-subcases-only |
| `GET /api/v1/agents/{agentId}/versions` | AGENT | mapped-subcases-only |
| `DELETE /api/v1/agents/{agentId}/workflow-binding` | PINNED | mapped-subcases-only |
| `PUT /api/v1/agents/{agentId}/workflow-binding` | PINNED, NUL-BIND-WORKFLOW | mapped-subcases-only |
| `POST /api/v1/conversations` | RUN, NUL-CONVERSATION | mapped-subcases-only |
| `POST /api/v1/conversations/{conversationId}/runs` | RUN, IDENTITY, ADMISSION, WORKFLOW-PINNED-DIGEST, RESUME-SOURCE | mapped-subcases-only |
| `GET /api/v1/conversations/{conversationId}/runs/by-key` | LOOKUP | mapped-subcases-only |
| `GET /api/v1/demo-items` | DEMO | mapped-subcases-only |
| `POST /api/v1/demo-items` | DEMO | mapped-subcases-only |
| `DELETE /api/v1/demo-items/{id}` | DEMO | mapped-subcases-only |
| `GET /api/v1/demo-items/{id}` | DEMO | mapped-subcases-only |
| `PUT /api/v1/demo-items/{id}` | DEMO | mapped-subcases-only |
| `DELETE /api/v1/documents/{id}` | KB | mapped-subcases-only |
| `GET /api/v1/documents/{id}` | KB | mapped-subcases-only |
| `GET /api/v1/documents/{id}/chunks` | KB | mapped-subcases-only |
| `GET /api/v1/health` | RUN | mapped-subcases-only |
| `POST /api/v1/intent-decisions` | INTENT, NUL-READ-INTENT | mapped-subcases-only |
| `GET /api/v1/knowledge-bases` | KB | mapped-subcases-only |
| `POST /api/v1/knowledge-bases` | KB, NUL-KB | mapped-subcases-only |
| `DELETE /api/v1/knowledge-bases/{id}` | KB | mapped-subcases-only |
| `GET /api/v1/knowledge-bases/{id}` | KB | mapped-subcases-only |
| `PUT /api/v1/knowledge-bases/{id}` | KB, NUL-KB | mapped-subcases-only |
| `GET /api/v1/knowledge-bases/{id}/documents` | KB | mapped-subcases-only |
| `POST /api/v1/knowledge-bases/{id}/documents` | KB, UPLOAD, NUL-UPLOAD | mapped-subcases-only |
| `POST /api/v1/knowledge-bases/{id}/retrieval-tests` | KB, NUL-READ-KB | mapped-subcases-only |
| `GET /api/v1/mcp-servers` | MCP-TOKEN, MCP-READBACK | mapped-subcases-only |
| `POST /api/v1/mcp-servers` | MCP, MCP-TOKEN, NUL-MCP | mapped-subcases-only |
| `DELETE /api/v1/mcp-servers/{id}` | MCP-READBACK | mapped-subcases-only |
| `GET /api/v1/mcp-servers/{id}` | MCP | mapped-subcases-only |
| `PUT /api/v1/mcp-servers/{id}` | MCP, NUL-MCP | mapped-subcases-only |
| `GET /api/v1/mcp-servers/{id}/tools` | MCP-READBACK | mapped-subcases-only |
| `POST /api/v1/mcp-servers/{id}/tools:refresh` | MCP | mapped-subcases-only |
| `POST /api/v1/mcp-servers/{id}/tools/{toolName}:call` | MCP | mapped-subcases-only |
| `GET /api/v1/providers` | PROVIDER | mapped-subcases-only |
| `POST /api/v1/providers` | PROVIDER, NUL-PROVIDER | mapped-subcases-only |
| `DELETE /api/v1/providers/{providerId}` | PROVIDER | mapped-subcases-only |
| `GET /api/v1/providers/{providerId}` | PROVIDER | mapped-subcases-only |
| `PUT /api/v1/providers/{providerId}` | PROVIDER, NUL-PROVIDER | mapped-subcases-only |
| `POST /api/v1/providers/{providerId}/connection-tests` | PROVIDER, NATIVE | mapped-subcases-only |
| `GET /api/v1/runs/{runId}` | RUN | mapped-subcases-only |
| `POST /api/v1/runs/{runId}/cancellations` | CANCEL, ERRORS | mapped-subcases-only |
| `GET /api/v1/runs/{runId}/events` | RUN | mapped-subcases-only |
| `GET /api/v1/runs/{runId}/events/stream` | SSE | mapped-subcases-only |
| `GET /api/v1/runs/{runId}/memory` | MEMORY-GATE | mapped-subcases-only |
| `GET /api/v1/runs/{runId}/memory/details/{refId}` | MEMORY, MEMORY-GATE | mapped-subcases-only |
| `POST /api/v1/runs/{runId}/memory/search` | MEMORY, MEMORY-GATE, NUL-READ-MEMORY | mapped-subcases-only |
| `GET /api/v1/tools` | CATALOG | mapped-subcases-only |
| `GET /api/v1/workflow-runs/{id}` | WORKFLOW, PINNED | mapped-subcases-only |
| `GET /api/v1/workflow-versions/{id}` | WORKFLOW | mapped-subcases-only |
| `POST /api/v1/workflow-versions/{id}/runs` | WORKFLOW, NUL-WORKFLOW-RUN | mapped-subcases-only |
| `GET /api/v1/workflows` | WORKFLOW | mapped-subcases-only |
| `POST /api/v1/workflows` | WORKFLOW, NUL-WORKFLOW, NUL-DEEP-CONFIG | mapped-subcases-only |
| `DELETE /api/v1/workflows/{id}` | WORKFLOW | mapped-subcases-only |
| `GET /api/v1/workflows/{id}` | WORKFLOW | mapped-subcases-only |
| `PUT /api/v1/workflows/{id}` | WORKFLOW, NUL-WORKFLOW, NUL-DEEP-CONFIG | mapped-subcases-only |
| `POST /api/v1/workflows/{id}/validations` | WORKFLOW | mapped-subcases-only |
| `GET /api/v1/workflows/{id}/versions` | WORKFLOW | mapped-subcases-only |
| `POST /api/v1/workflows/{id}/versions` | WORKFLOW | mapped-subcases-only |

## F01–F38及边界

| 功能 | 子场景 | 未测或独立证据 |
|---|---|---|
| F01 | RUN | 数据库故障与全依赖就绪；health只是存活 |
| F02 | PROVIDER | 真实模型与所有并发引用 |
| F03 | CREDENTIAL | DNS固定解析/运维误配外部自定义秘密 |
| F04 | NATIVE, HTTPCLIENT | 真实四家供应商协议完整验收 |
| F05 | AGENT, CATALOG | 真实浏览器全部Agent CRUD；管理分页400/夹值未统一（SPEC-API-PAGINATION-001） |
| F06 | AGENT, ARCHIVE, PINNED, MCP | 三类能力任意组合；checksum纵深防御 |
| F07 | AGENT, PINNED | 多实例滚动重叠 |
| F08 | DEMO | 全组合压力与数据库故障 |
| F09 | RUN, SSE | 真实模型/代理故障全排列；本地浏览器单列 |
| F10 | IDENTITY, LOOKUP, RESUME-SOURCE | 刷新丢失页面身份、数据库不可用等所有完整性错误排列；005全后端门禁待环境恢复 |
| F11 | CANCEL, HTTPCLIENT, PINNED | 远端副作用及JDBC强制中断；所有调用2s SLA |
| F12 | SSE | 64正常标签容量/公平配额/慢读硬期限 |
| F13 | GATE, KNOWLEDGE-GATE | 答案语义真实性 |
| F14 | REPLAN | 真实模型计划质量 |
| F15 | HISTORY, SHUTDOWN | fork JVM、kill-9、重启总budget/replan计数 |
| F16 | LEASE, MCP | 分布式lease/写审批 |
| F17 | HISTORY | 恶意DB写入者/外部写exactly-once |
| F18 | CONTEXT | 精确token和金额预算 |
| F19 | MEMORY, MEMORY-GATE | PG先截断过滤质量、普通ref正向对照 |
| F20 | CHILD | 真实子Agent worker及启动监听顺序 |
| F21 | INTENT, EVAL | 在线主链路调度/shadow |
| F22 | TIME | 真实模型；本地真实浏览器单列 |
| F23 | KB, UPLOAD, INDEX-DIALECT | 大文件完整索引/并发归档；真实断网及连接池压力 |
| F24 | KB | 真实embedding效果；原文冻结不等于向量/排序冻结；PG专项另列完整门禁；分页400/夹值决策待定（SPEC-API-PAGINATION-001） |
| F25 | KNOWLEDGE-GATE, MEMORY-GATE | 语义grounding/来源失败事件口径/DB转Gap |
| F26 | WORKFLOW | 全角空格、旧转义与裸标点迁移；分页夹值与KB400未统一（SPEC-API-PAGINATION-001） |
| F27 | PINNED, KNOWLEDGE-GATE, WORKFLOW-PINNED-DIGEST | 成功落盘后崩溃的Workflow复用；同时篡改Agent与Workflow的数据库写者防御 |
| F28 | MCP | 完整MCP session/SSE conformance与真实第三方 |
| F29 | MCP-TOKEN, CREDENTIAL | 全局撤销/密钥轮换恢复演练；浏览器结果单列 |
| F30 | CREDENTIAL, HTTPCLIENT, MCP | DNS rebinding窗口/完整出站网络隔离 |
| F31 |  | 浏览器独立结果，不由Java计数推断；真实全CRUD未穷举；在途GET人工重连/取消查询按钮新增受控用例，005完整门禁待环境恢复；失效resume循环已有RUN-INPUT-001证据 |
| F32 |  | 受控画布/diff smoke见浏览器结果；真实编辑全链路未验收 |
| F33 |  | MCP编辑和Token由browser-live独立证明，不由本表推断 |
| F34 | ERRORS, ADMISSION, HTTPCLIENT, NUL-UPLOAD, NUL-KB, NUL-AGENT, NUL-WORKFLOW, NUL-PROVIDER, NUL-MCP, NUL-CONVERSATION, NUL-WORKFLOW-RUN, NUL-BIND-KB, NUL-BIND-MCP, NUL-BIND-WORKFLOW, NUL-BIND-TOOLS, NUL-ENABLED-TOOLS, NUL-DEEP-CONFIG, NUL-READ-KB, NUL-READ-FROZEN, NUL-READ-MEMORY, NUL-READ-INTENT | 不是每条路由全部错误组合；NUL范围外见SPEC_INPUT_HYGIENE |
| F35 |  | 看本次migration-postgres独立零skip清单；非所有PG并发排列 |
| F36 |  | 132重新发布尚未执行；备份恢复演练未完成 |
| F37 | EVAL | 真实LLM/RAG准确率、端到端延迟和费用 |
| F38 |  | harness独立命令记录；不防恶意伪造manifest，AUDIT-004开放 |
