# 全功能验收矩阵

以下是目标断言与现有测试入口，不是本轮通过列表。A=HTTP/应用集成，U=纯单测，P=PostgreSQL，B=浏览器，X=真实外部依赖，O=运维。类型缺失时明确补测；不能用 U/A 替代 P/B/X。
所有 Java 测试位置可从 `git ls-files '*Test.java'` 定位；完整 HTTP 方法/请求类型见 [HTTP_API](HTTP_API.md)。

## 管理与配置

| ID | 场景与必须断言 | 失败/边界 | 已有入口及核验层 |
|---|---|---|---|
| F01 | health 响应 HTTP200 / code200 / 固定文案 | 数据库断开时不能把存活接口误解为就绪 | B: chat；O: Actuator/部署；缺专用 A |
| F02 | Provider CRUD、四种类型、模型显示名/调用ID分离、独立健康 | MOCK 创建拒绝、重复默认模型、缺引用、禁用、重复名、并发 | A: ProviderApiIntegrationTest；P 并发引用需补 |
| F03 | 凭据只传授权头、管理响应不返回值、失败脱敏 | auth 缺省/更换类型/缺 env；URL 私网/重定向/混合 DNS | U: ProviderUrlPolicyTest、LlmHttpClientTest；A: ProviderApiIntegrationTest；X 未覆盖 |
| F04 | 三原生+兼容协议文本/tool_calls/result正确配对、流增量重组 | 401/429/5xx、首包前/后错误、断流、取消、超时、无重发 POST | U: NativeProviderModelClientTest、LlmHttpClientTest；不等于 X 四家验收 |
| F05 | Agent 草稿 CRUD/参数校验/动态工具目录/批量查询 | 空名/过高温度/未知工具/禁用模型/重复名 | A: AgentApiIntegrationTest；P: PostgresConcurrencyIntegrationTest |
| F06 | 四种能力独立绑定，发布快照/digest/未发布差异正确 | 草稿改动不改版本；删除只归档且不复用名 | A: AgentApiIntegrationTest、McpServerApiIntegrationTest |
| F07 | 发布 v2 后旧会话用 v1，新会话用 v2；归档阻止新会话 | 并发发布、Provider 配置变化与版本边界 | A/P: AgentApiIntegrationTest、PostgresConcurrencyIntegrationTest；缓存 U/A: AgentCacheIntegrationTest |
| F08 | DemoItem 完整 CRUD、分页、自动填充、逻辑删除 | 空名、非法 page/pageSize、缺记录、时间格式 | A: DemoItemApiIntegrationTest；U: CommonContractsTest |

## Chat / Runtime / 状态与记忆

| ID | 场景与必须断言 | 失败/边界 | 已有入口及核验层 |
|---|---|---|---|
| F09 | 发消息→Run202→SSE delta→工具→终态→事实回读 | 只合并当前消息；终态/助手消息/最后事件同事务；提交后投影；streamUrl 前缀 | A: RunFlowIntegrationTest；P: PostgresConcurrencyIntegrationTest；B: chat.spec.ts |
| F10 | 并发同幂等键只建一条消息/Run；同体重放200、异体409 | 空/缺 key、同 key 跨会话、resume 内容不同 | A/P: RunFlowIntegrationTest、PostgresConcurrencyIntegrationTest |
| F11 | 取消持久化并中断阻塞 Provider/MCP，终态单赢家 | 运行前/首包后/工具前取消；终态再次取消；2s SLA | U: ToolRuntimeTest、LlmHttpClientTest、RunWorkflowControlTest；A: McpProtocolClientReliabilityTest、WorkflowRunControlIntegrationTest；实际JDBC/远端副作用停止需独立证明 |
| F12 | 事件有序持久、Last-Event-ID 只放游标后、终态关闭流 | 回滚不外发、提交回调乱序/订阅交错无重复、terminal写失败回滚success、断线≠取消 | U: RunEventBrokerTest；P: PostgresConcurrencyIntegrationTest；MCP A replay；B 真断线/取消需补 |
| F13 | 六出口与 FinishGate：回答、required Claim VERIFIED、无 blocking Gap | 空回答/缺证据/未闭合工具调用/权限禁止 | U: ExecutionContextStateTest、QueryLoopTest；Knowledge 注入不自动提供 gate evidence |
| F14 | 参数错误 LOCAL_REPLAN；超时有限 RETRY；缺参数 CLARIFY；拒权 INTERRUPT | budget 不重置、失败点≠根因点、无替代 ASK_HUMAN/no-progress | U: PlanStateMachineTest、QueryLoopTest |
| F15 | checkpoint恢复不重放已完成尝试；重启 RUNNING 收敛 | 取消状态恢复；损坏快照；NEEDS_INPUT 通过新 Run resume | U: QueryLoopTest；A: ContextMemoryIntegrationTest；重启真实进程 O 待补 |
| F16 | 固定 capability/schema revision、lease 执行前二次校验 | 取消/attempt 变化/审批后漂移不得执行 | U: ToolRuntimeTest；A: MCP immutable snapshot tests |
| F17 | canonical history operationId 语义幂等，持久化→回读→revision→投影 | 同 ID 异摘要冲突；失败 ack 不提前 | U: QueryLoopTest；A: RunFlowIntegrationTest；故障点注入需扩展 |
| F18 | Context 独立 input/output/reserve/safety 预算；先归档后压缩重测 | 即使压缩仍超限拒绝；原文不丢；引用完整 | U/E: ContextManagerTest、ContextManagementEvaluationTest |
| F19 | 最近 K 轮原文+旧摘要目录；search导航→detail原文→VERIFIED | 跨 Conversation、digest不匹配、重复调查预算、摘要不能证明事实 | A: ContextMemoryIntegrationTest；U/E: HistoryRecallEvaluationTest、QueryLoopTest |
| F20 | 子任务 delivered/claimed/consumed；父成功提交后消费 | 无执行者启动标 lost；父失败不得 ack | U/A: ChildAgentTaskTest、ChildAgentTaskIntegrationTest；无真实 worker/调度器 |
| F21 | Intent 四出口、确定性短路、结构模型软判断、低置信/缺槽/歧义澄清 | 结构不合法/模型故障未知，危险动作不执行 | U/A/E: Intent*Test、LayeredIntentRouterTest；仅 preview、不接管 Run |
| F22 | Demo 时间与计算器只调用已授权工具，跨轮不复用旧 observation | greeting 后再问时间，多轮时间，英文字母误触发 | U: MockModelClientTest；A: RunFlowIntegrationTest；B: chat-time.spec.ts |

## Knowledge / Workflow / MCP

| ID | 场景与必须断言 | 失败/边界 | 已有入口及核验层 |
|---|---|---|---|
| F23 | KB CRUD→TXT/MD上传→持久索引→chunk/digest→检索→归档 | 空/超大/错误类型/重启/归档竞态；分页负值；非法 overlap | A: KnowledgeApiIntegrationTest；多条管理读取/更新/归档路径需补 |
| F24 | FTS+向量/RRF 正确过滤，canonical chunk可回读；旧语料快照稳定 | 空命中/库停用/失败；H2 不测 PG SQL；中文分词限制 | A/P: KnowledgeApiIntegrationTest、PostgresConcurrencyIntegrationTest；embedding 为64维 hash bootstrap，不是真实模型 |
| F25 | Agent 知识绑定发布固定 revision、注入来源事件 | 空命中/检索异常不得无依据宣称 grounded；最终引用需校验 | A: AgentApiIntegrationTest 目前只验证空库事件，grounding 闭环不足 |
| F26 | Workflow 创建/修改/校验/发布/历史/归档/试跑/轨迹 | 无环可达不等于变量必经；死路/重复分支/END出边/未知变量 | U/A: SPEC_WORKFLOW_GRAPH 记录25项单测+5项HTTP通过；列表/归档等全覆盖仍需VERIFY，资源冻结/取消另验 |
| F27 | Chat 固定已发布 Workflow 执行，图更新不变旧行为 | KNOWLEDGE节点语料变化；取消中执行；超时/空输出 gate | A: AgentApiIntegrationTest 只覆盖 TEMPLATE 版本；需扩展 |
| F28 | MCP Server CRUD、发现新 revision、READ工具调试、绑定 QueryLoop | 假工具/非READ、schema漂移、不可用/超时/取消/SSE replay | A: McpServerApiIntegrationTest、McpProtocolClientReliabilityTest |
| F29 | MCP Token KEEP/TOKEN/REFERENCE/CLEAR；GCM随机nonce/所有者绑定；旧快照旧凭据 | 缺/错主密钥、篡改、跨Server、畸形JSON、回显、清除不等于全局撤销 | U: McpCredentialCipherTest；A/P: AbstractMcpCredentialContract 两环境；B: mcp-token-live.spec.ts |
| F30 | 出站 URL/risk/headers/大小的安全边界 | DNS rebinding、协议重定向/元数据/用户信息、工具描述不可信 | U/A: ProviderUrlPolicyTest、McpServerApiIntegrationTest；完整 MCP session/SSE conformance 未实现 |

## Console / 工程 / 部署 / 效果

| ID | 场景与必须断言 | 失败/边界 | 已有入口及核验层 |
|---|---|---|---|
| F31 | 六个页面可导航、表单校验、分页、空态、loading、失败保留输入 | 窄屏、后端错误、重复提交、取消不修改、请求竞态 | B: management.spec.ts 是路由 mock smoke；不能称真实 CRUD 全覆盖 |
| F32 | Workflow 画布与 JSON 使用同一 DSL、校验/试跑/diff | 非法连线、编辑未保存、旧版本 diff | B: management.spec.ts 仅打开画布/diff；真实图编辑需补 |
| F33 | MCP 编辑原ID、Token不回填、关闭清空、保存/替换/清除 | API失败保留本次输入；切换操作不误传 token | B: mcp-edit.spec.ts mock；mcp-edit-live/mcp-token-live opt-in 真实链路 |
| F34 | Reactor依赖、统一Result/异常、线程池隔离、分页/时间/Redis基础 | 池饱和、Redis停机、事务后缓存、解析/缺header/参数类型错误 | U/A: MavenStructureTest、CommonContractsTest、AgentCacheIntegrationTest；统一4xx边界待补 |
| F35 | Flyway V1-V20从空库/升级不丢版本，唯一约束/加密存储 | 不能将 Testcontainers skip 当通过；数据真实且隔离 | P: migration scope（3个类）；不是全部业务的 PG 覆盖 |
| F36 | 启停脚本、失败清理、PID归属、备份恢复、SSE代理、TLS、前缀 | 不杀其他进程；构建与已部署SHA一致；Token主密钥不可重置 | O: start/stop/deploy 脚本；本轮需重新验证授权范围 |
| F37 | 版本化评测数据、成功率/召回/覆盖/重复调查/成本延迟 | 测试集与参数不能混用；mock token/延迟不能称真实P95 | E: IntentEvaluationDatasetTest、HistoryRecallEvaluationTest、ContextManagementEvaluationTest；真实供应商效果未验收 |
| F38 | Harness单任务、权限、baseline/checkpoint/evidence、生成进度 | 不同高风险动作需approvalRef；失败不标完成；测试记录版本对齐 | harness/tests/test_harness.py；全接口门禁 ApiContractInventoryTest |

## 本轮覆盖报告要求

每次验证记录 `caseId / commit / environment / fixture / command / expected / actual / status / evidence`。status 只取 pass/fail/not-run；not-run 需原因，不能以“测试类存在”填 pass。
最少三层独立报告：67个接口库存一致性；F01-F38行为矩阵；本地/132部署验收。任何外部真实模型、真实 MCP 凭据缺失均单列，不以 mock 外推。未通过项归入后续原子任务，不删规格降低分母。

历史召回12题 toy 排序性能协议：先报告冷启动单轮P95，再预热5轮、采样20轮（240样本），稳态P95保持小于50ms门禁，同时报告稳态最大值。该数值只包含进程内排序/本地bootstrap embedding，不包含数据库/网络/模型生成；禁止与旧冷启动数值直接比较后宣称产品提速。质量断言与黄金标签不随计时协议调整。
