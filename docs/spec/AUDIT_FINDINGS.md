# 审计发现（源码基线 973257c）

这是事实与待验证问题清单，不是任务状态板；实现状态看 harness/tasks.json。编号保留用于回归，不能以修改文档代替修复行为。

## 初始反例与后续修复证据

### A01 Knowledge 空命中/检索失败不形成显式缺口

`RunApplicationService.knowledgeContext` 捕获异常后继续、空 citations 返回空字符串。QueryLoop 得不到“知识证据不足”的结构化状态；`AgentApiIntegrationTest.publishesKnowledgeRevisionAndPreparesItBeforeQueryLoop` 只验证检索事件，不验证拒答/Claim grounding。这不能证明 SPEC 的“空命中不编造”。
要求：隔离 fake retrieval 返回空/抛错，断言不生成自称有依据的完成；有证据时必须保存可验证来源；无知识绑定普通聊天不能被误伤。产品选择严格拒答/受限回答的契约需清晰，不把 prompt 当代码门禁。

SPEC-KNOWLEDGE-FINISH-001 后续实现来源完整性门禁：空/部分读取失败模型零调用，缺/伪引用 NEEDS_INPUT；canonical 回读只把来源 Claim 标为 VERIFIED，模型语义保持 UNVERIFIED，明确不承诺蕴含/无答案准确率。新增向量候选 floor 不能替代语义充分性；关键词仍可能无关。恢复保留原引用映射，Workflow 空候选失败。见 `SPEC_KNOWLEDGE_FINISH.md` 与对应 evidence；不将这项局部交付称为完整 grounding。

### A02 Workflow 执行缺取消与全局截止时间传播

`RunApplicationService.executeWorkflow` 只在执行前检查取消；`WorkflowEngine.execute(versionId,input)` 没有 cancellation/deadline 参数，只有50步上限。调用返回后可直接提交 COMPLETED，不经 QueryLoop FinishGate。TEMPLATE 快速通过的测试不能证明阻塞 KNOWLEDGE 节点取消可靠。
要求：阻塞检索节点的确定性测试；取消后不得启动下一节点/提交成功；截止时间包括检索且不因路径切换重置。记录失败/取消节点，不引入通用回滚。
控制链路修复`0ed58fe`/`40f90d2`/`3d867e7`：模块38项、HTTP/H2 7项通过，V21真实PG升级及既有PG回归零跳过通过。证据见`docs/evidence/SPEC_WORKFLOW_CONTROL.md`，保留首轮Docker磁盘不足失败。尚不证明实际JDBC阻塞中断；Workflow成功标准与知识证据门禁仍需A01/A03后续验收。

### A03 已发布 Workflow 的 KNOWLEDGE 节点读取可变知识库

`WorkflowEngine.executeNode` 使用 `knowledge.search(base,query,topK)`，未固定 corpus revision。现有冻结 Workflow checksum 只固定 DSL，不固定节点关联的语料。需验证“发布图→更新/归档知识→旧图执行”的行为，并明确资源快照与 schema checksum 两者不可混淆。

后续 SPEC-WORKFLOW-KNOWLEDGE-001 用 HTTP/H2 红灯确认旧版本随文档变化，改为发布冻结 corpus 并进入 DSL/checksum，执行校验完整 manifest 和原文摘要；归档后保留带正确摘要的冻结引用。真实 PG 回归额外发现并修复 Instant JDBC 绑定失败。新鲜日志与最终门禁见 `docs/evidence/SPEC_WORKFLOW_KNOWLEDGE.md`。旧未冻结知识图显式拒绝、不改历史；空/无关命中及 FinishGate 不在本片完成范围。

### A04 Workflow 图校验没有变量可达/必经与所有路径终止门禁

`WorkflowGraphValidator` 目前只查类型、START/END计数、边引用、默认分支、整体可达与环。未验证每条可走路径到 END、非条件多出边、模板变量是否来自必经节点；运行时能否 fail closed 还需反例测试。课程 hify-cc 的修复不能算本仓库已具备。
本仓库修复 `73e73c0`：25项模块单测及5项独立H2/HTTP回归通过，见 `docs/evidence/SPEC_WORKFLOW_GRAPH.md`。执行前复查DSL，旧非法版本拒绝且不改写；已发布图的知识语料稳定性和取消另属A03/A02，不混记为完成。

### A05 HTTP 错误边界待补

`GlobalExceptionHandler` 未专门覆盖缺 header/参数类型、405/415 等常见客户端错误，存在被兜底 Exception 转成500并记录异常的风险。需通过公开 MockMvc 请求复现（尤其 `Idempotency-Key` 缺失与敏感输入），不能只测直接 handler 调用。
隔离 standalone MockMvc（真实 handler + 最小 Controller）已确认四项全返回500，见 `docs/evidence/spec-probes/README.md`；应用路由回归待补。
公共层修复 `d03c02a`，7项MockMvc通过，详见 `docs/evidence/SPEC_COMMON.md`；全应用接口回归仍保留为后续门禁。

### A06 Provider 取消可能只退出等待，未停止后台任务

`LlmHttpClient.post` 和 `CircuitBreakerService.execute` 在循环中调用 `control.throwIfCancelled()`，但只有 InterruptedException/超时分支执行 future.cancel(true)，没有 finally 取消未完成 Future。现有 blocking-post 测试只断言调用者一秒内抛异常，没有断言线程/底层调用停止。需用 latch 验证 worker 收到 interrupt、取消前不调网络、取消后不继续重试。
隔离诊断已确认：预取消仍提交1个任务；调用者抛 ExecutionCancelledException 后，worker 在清理前500ms内没有收到中断。证据同上。
公共层修复 `5a36569`：Future清理、排队/重试资格复查、流式预检与断连、饱和时拒绝而非CallerRuns。公共层30项通过；真实Provider/全应用故障矩阵仍需复跑。

### A07 SSE 提交与订阅存在事务/终态窗口

`RunEventBroker.publish` 在 @Transactional 方法内 saveAndFlush 后立即向 SSE emit，早于事务 commit；`subscribe` 又以 Run 终态决定直接 complete，而终态事件可能尚未投影。需验证回滚事件不外发、终态与最后事件间隙订阅不漏 terminal、并发序号与锁移除不产生双赢家。现有 PG 用例等待终态事件后订阅，不覆盖这些窗口。

修复 `a1c2015`：Run行锁分配序号、提交后按订阅游标追赶、固定条带锁、Run终态/助手消息/terminal事件同事务。受控旧实现4项中2项失败，修复后初始9项通过；真实PG 6项和HTTP/H2取消1项通过、零跳过。首次独立PG命令因本机代理导致JDBC连接失败，记录保留。最终Harness与全应用门禁以 `docs/evidence/SPEC_SSE_COMMIT.md` 和机器任务证据为准，尚未部署。

### A08 Chat 页面会话切换与收尾异常没有隔离

`ChatView.vue` 的“新会话”在执行时仍可点击，清空消息但不关闭局部 EventSource；旧 delta 按旧数组下标写入新会话，可能抛异常或污染消息。组件离开时也没有关闭连接。`finish` 先标记 settled 并关闭流，再 await getRun，缺少失败处理；回读失败会令 running 永远保持 true。已有 live happy-path 不能覆盖这些情况。
要求：浏览器注入受控事件源，验证运行期间不能切新会话、离开后连接关闭且旧事件不写新页面、终态回读失败可恢复而非无限 loading、最终持久化文本能纠正重复/漏 delta。取消接口失败也必须显示明确错误并允许再次取消。
前端修复 `73f8938`，测试隔离 `00a10dd`：8项受控浏览器回归、typecheck/build通过，见 `docs/evidence/SPEC_CHAT_UI.md`。不替代A07服务端事务修复或部署端真实Chat验收。

## 历史静态复核原始索引（0975782，不是当前状态）

来源：mymacclaude 消息 `om_x100b63204ee2a8a0c2cced58d25ff1c`。对方明确本轮仅静态阅读；不能标成独立测试通过。后续提交需复查是否已消除相关路径。

- A1：MCP任意env/system引用会读取进程主密钥；本地检查发现Provider有同源路径。已用4个失败反例确认，70a5f33实现默认拒绝的引用/精确目标绑定，TOKEN换地址禁止KEEP；模块/HTTP与真实PG门禁通过，尚未部署，见SPEC_CREDENTIAL_BOUNDARY.md。
- A2/A3/A4/A5：对应既有A01/A03，另需修正Claim为空的FINISH语义、Workflow未走FinishGate、归档后canonical引用回读矛盾。
- B1：取消/本地拒绝计入供应商熔断已由3个红灯复现；局部执行拒绝类型化、按调用控制释放breaker许可，63项common/provider回归通过，见SPEC_PROVIDER_LOCAL_FAILURE.md。B2当时未收敛RUNNING，后续446688a/656b61a已加真实拒绝执行器测试与单实例owner，见下方增量；数据库故障与关机边界另列，不再写成全项未修。
- C1/C2/C3/C4：历史反例为新Run创建期间取消旧Run、失效resume、网络未知时新key重复创建、空gapIds的NEEDS_INPUT；另有重连和终态补读竞争。C4旧版本的正常生产可达性未证明，但后续已用受控响应验证防御性不可恢复提示；参见Chat专项，不表示健康后端应产生空Gap。
- D1/D2：图校验与50步执行上限不一致、条件表达式解析未在发布校验，字面量含运算符歧义。
- E1：线程池关闭中断可能误记用户取消而失去恢复；E-P2包括两张Run终态竞态、预取消重复事件、拒绝错误回显、取消标志被覆盖、跨实例取消非目标。事件相关先对照a1c2015复核。
- 其余P2：multipart错误500、主密钥错误启动未检测、停用Server对历史能力语义、LLM与索引共池、IllegalArgumentException回显、standalone MockMvc表述、非法旧DSL分类、图测试缺项及模板字面量边界。

以上是反例索引，不是完成状态；任务与验证进展仍只在Harness机器状态中记录。

F切片独立复核（0975782..fc7bd51）仍为静态阅读：`RunEventBroker` 锁内同步send/afterCommit可能被慢客户端阻塞；跨Run/未知Last-Event-ID需归属检查；取消抢赢时Workflow终态投影及两表一致性仍有缺口。事务保护断言是裸对象单测，生产代理的REQUIRED会自动开事务；受控afterCommit交错测试不是实际多线程竞态。PG序号唯一/回滚投影测试已跑，但不能据此宣称慢客户端隔离和全部重连边界已完成。

F后续：两个旧实现反例红灯；改为有界订阅worker、提交/心跳只唤醒、历史分页与正数游标归属检查。真实Tomcat不读socket反例验证提交3ms、另一Run可达、无持有数据库连接、写超时清理后replay；见SPEC_SSE_BACKPRESSURE.md。Workflow两表终态竞态仍交SPEC-RUN-ADMISSION-001；不能据此标成已修。

G切片独立复核：无P0/P1；P2-1指出受保护命名空间漏掉项目实际HIFY_DB_*及JVM TLS配置，已以误授权红灯证实。后续按命名空间补拦截，不全面禁止合法应用的SECRET/PASSWORD名称。P2-2不改变路径授权范围，OPERATIONS明确Provider去尾斜杠、MCP保持精确路径。P2-3新增已发布AgentVersion旧引用的真实ToolRuntime零外发测试，H2/PG证据见SPEC_CREDENTIAL_BOUNDARY.md的G补充。

## 2026-10-04 对齐至 641fdcb（专项证据，不是全量验收）

原始反例不删除。各专项的红灯、修复提交与门禁见以下 evidence；对方的复核都是静态阅读，不写成独立复跑。任务状态仍只由 tasks.json 维护。

- A01：来源门禁后续封闭 memory/catalog/search/detail/summary 旧索引出口；知识 Run 不做 layered 压缩。KnowledgeShutdown H2/PG验证回读期间关闭保持RUNNING并写 interrupted。见 `../evidence/SPEC_KNOWLEDGE_MEMORY.md`。只核对引用来源，不验证回答语义；wrapped suspension/基础设施失败分类/界面范围说明另登记。
- A03：服务端 publication 标记、原始 DSL checksum、Agent绑定前复检、KB排序加锁及真实PG检索交错见 `../evidence/SPEC_WORKFLOW_KNOWLEDGE.md`。H2只证明隔离级别设置，不证明交错读；向量未冻结。索引侧连接检测和Agent绑定checksum运行比对仍有P2。
- A04/D：最长路径和运行计步一致、引号感知解析且插值不执行语法，见 `../evidence/SPEC_WORKFLOW_GRAPH_REVIEW.md`（50项模块/HTTP）。全角空格、旧字面量转义语义和旧裸标点兼容、错误定位/事件投影另登记，不宣称任意旧DSL完全兼容。
- A06/B1：取消/本地拒绝释放熔断许可，模型期限先结算再中断worker；默认45s模型/60sRun的挂住路径已复验。Run先到期、共享执行器排队样本归属仍在 `SPEC-PROVIDER-SAMPLING-001`，不宣称完备网络采样。
- A07/F：有界订阅worker和游标归属见 `../evidence/SPEC_SSE_BACKPRESSURE.md`；全局64名额、无按Run/IP限额和慢读硬期限仍有边界。Workflow执行事实/交付v2投影见 `SPEC_WORKFLOW_SETTLEMENT.md`，不能把取消后的workflow.completed当父Run成功。
- B2/E1/history：本地dispatch owner消除单实例双调度，关闭不冒充用户取消，正常完成仍可提交；已提交 model/tool 可恢复，原始摘要先验后做JSON键序无关比较。见 `SPEC_RUN_SHUTDOWN.md`、`SPEC_HISTORY_RECOVERY.md`。跨实例lease、真实fork JVM、未提交副作用exactly-once未实现；工作流成功后进程崩溃可能重跑。
- G：保留 HIFY_MCP/HIFY_CREDENTIAL 命名空间阻止宽松绑定别名，误授权也拒绝；详情见 `SPEC_CREDENTIAL_BOUNDARY.md`。禁止把“可引用env”写成任意读取进程配置。
- A08/C：`18d2dee`、`496aae5`的21项受控浏览器回归修复创建中取消旧Run、过期resume、同key快照和终态补读竞争，见 `../evidence/SPEC_CHAT_LIFECYCLE_REVIEW.md`。对方随后指出**新P1**：后端先验证Provider/会话再查key，已提交但客户端未知时，Provider停用导致重试与取消都失败且页面无放弃出口。需真实后端红灯，不能用“500→403继续unknown”的浏览器测试当正确性证明。结果不明取消重POST可能创建任务、明确4xx丢输入、200即断无限补读为相关P2。

这里的67项是接口集合，38项是行为验收场景；修复过某个场景不代表此组所有接口/负路径已经验收。SPEC-VERIFY-001须保留pass/fail/not-run以及证据版本。

## 2026-10-04 复核补录（daec369后的证据边界）

旧段的“待修”保留其时间基线，不代表当前任务状态。Chat新P1已由1ce9cfb..daec369修复：查重早于可变准入、GET lookup不创建、取消不POST、显式放弃与输入保留、有界短断线。3项真实HTTP/H2测试+28项受控浏览器证据见 `../evidence/SPEC_CHAT_SUBMISSION_RECOVERY.md`。独立review仅静态阅读；新增4条P2（人工重连、放弃resume上下文、取消文案、createConversation超时）交SPEC-CHAT-LIFECYCLE-004。库存现为68条，不回写历史67。

下表补齐原review中未关闭的具体反例；仅是定位索引，状态仍由tasks.json维护：

| 对应任务/说明 | 具体反例与验证缺口 |
|---|---|
| SPEC-KNOWLEDGE-FINISH-004 | 部分来源失败先发knowledge.retrieval.completed再失败；wrapped suspension未沿cause链判定；DataAccessException变成用户Gap；UI缺来源核验范围说明 |
| SPEC-KNOWLEDGE-INTEGRITY-003 | DocumentIndexingService.isPostgres另借连接，元数据读取失败返回false导致PG漏写向量却成功；运行时未比Agent绑定的workflow checksum |
| SPEC-MEMORY-FILTER-001 | PG前100候选先截断后按来源过滤，普通Run证据会被挤掉；缺同会话普通ref可见的正向端到端对照 |
| SPEC-HISTORY-RECOVERY-003 | recallLatency/replanDecisions恢复归零；触发replan的末工具没有后续恢复记录时重建UUID；观察事件重放会重复 |
| SPEC-RUN-BUDGET-001 | Chat重启重新分配完整runTimeout；Workflow按createdAt扣减，两条路径不一致 |
| SPEC-RECOVERY-ADMISSION-001 | 拒绝后get/finishTerminal数据库报错可留RUNNING；启动恢复超过104容量直接FAILED；WorkflowRecovery整体UPDATE失败会阻止启动，逐行catch不涵盖它；tasks.json分别列出故障注入验收 |
| SPEC-CHILD-RECOVERY-001 | ChildAgentTask恢复与Run恢复监听器缺确定顺序；新认领任务可能被convergeLost误标，尚需交错测试 |
| SPEC-WORKFLOW-RECOVERY-001 | requireVersion/DSL校验/建run失败、成功后response读取失败仍可MODEL_ERROR且缺workflow投影；END SUCCEEDED与父CANCELLED不一致；已成功workflow落库后崩溃可能重跑、旧结果无投影 |
| SPEC-WORKFLOW-GRAPH-003 | workflow.started早于校验；U3000 trim不一致；旧quoted反斜杠改按JSON转义；裸help!/A&B/退款(急)现拒绝，迁移提示未覆盖；错误无nodeKey/发布顺序 |
| SPEC-SSE-BACKPRESSURE-002 | 全局64独占线程可被空闲连接占满，无Run/IP配额；180秒只在send之间检查，慢读不等于硬总期限；缺丢唤醒/释放/慢读等确定性交错证据 |
| SPEC-PROVIDER-SAMPLING-001 | Run期限先于model时只释放不计超时；beforeAttempt在本地排队前，故障样本可能错误归Provider；不宣称所有挂起都能熔断 |
| OPERATIONS的凭据部署限制 | -D/JAVA_OPTS传密钥可能经sun.java.command等误授权外送；当前compose/env入口未用此方式，不能泛称所有JVM启动变量受保护 |

历史审计backend495/406/89与Chat backend498/409/89的逐类可提交摘要见 `../evidence/SPEC_AUDIT_FOLLOWUP.md`；它们不是新跑PG的证据。受控关闭时序、mock管理浏览器、H2交错无效等限制须在F15/F31/F38就地说明，不藏在旧日志里。

## 已核对的文档漂移

SPEC-RUN-INPUT-001增量64bda57：HTTP/直接service超限预检，指定23505唯一约束才重放，回滚后会话不存在404、其他完整性错误500；明确拒绝resume有新会话出口且不自动恢复澄清文本。旧3个后端409反例及浏览器失效循环先红后绿，最终543项后端/32受控浏览器见SPEC_RUN_INPUT。未部署；独立review静态阅读、摘要SHA与源码树核对无P0/P1，未重跑测试。PG约束名经Hibernate依赖英文报文、NUL输入PG500归SPEC-RUN-INPUT-002；原重复gapId撑爆P2已撤回（record先distinct/50上限、未知gap落库前拒绝）。历史反例段落保留。

SPEC-DEPLOY-002独立review在隔离archive用sh/dash实测9项与边界：新P1为stderr关闭时on_exit的printf因set-e中止，旧服务无法恢复（SPEC-DEPLOY-003）；0/000健康次数与成功后信号也归003。响应200或dict ID遗漏、KB类型、中断错误、假SQL区分、V23写死/索引RUNNING归SPEC-DEPLOY-004。未连接132，426de39脚本禁止再次发布直至P1复验。

132发布eeb385e..3e47d13独立review无P0/P1（未远端验证）。SPEC-DEPLOY-002跟踪六条P2：后迁移失败停服/60s等待可用性；SSH/HUP/INT/TERM处理；静态备份不自动恢复的计划漂移（本次仅纠正文案）；停止前新Run及直接Workflow准入窗口；smoke检索无正文/ID断言；超限意外202漏登记及逐项清理中断。TLS仅合成测试关闭校验、同盘备份及96%磁盘为明确边界，不能冒充固定证书/恢复演练。

2026-10-04总验收eeb385e独立复核无P1，四条P2不推翻本轮通过：SPEC-VERIFY-002跟踪send已阻塞后再次publish的直接断言、路由随子场景partial以及可离线复算方法报告；SPEC-API-PAGINATION-001跟踪管理分页契约差异，未做产品决策；SPEC-CHAT-LIFECYCLE-005跟踪在途GET下人工重连、取消查询按钮名、跨会话来源文案、失效resume循环（最后一项亦见RUN-INPUT-001）。报告目前mapped-subcases-only不是通过标志，原始XML未提交所以仅凭Git不能重算，边界不隐藏。

2026-10-04 Chat004（83055fb/a2ee546）独立静态review与摘要SHA核对关闭人工重连、澄清放弃、取消文案、建会话超时、缺header缓存头及非法resume误归类。create仍宽泛捕获DataIntegrityViolation：超长消息/并发会话删除可变成40900，归SPEC-RUN-INPUT-001；abort后失败的fulfill不能作为迟到成功响应证据，SPEC_CHAT_MANUAL_RECOVERY已收窄措辞。管理001的夹值契约来源、终态取消种类、旧会话执行与非法resume反向对照归SPEC-API-BEHAVIOR-002，不以旧窄测替代。

2026-10-04新增复核：HTTP切片fa5f48f..9aac4e7的上传P1归SPEC-UPLOAD-BOUNDARY-001（应用/两nginx没有显式上限，原10MB/JSON承诺不可达）；f64a690门禁旧问题已关闭，恶意/手工改manifest或降级历史schema仍可绕过的两条P2归SPEC-AUDIT-004。reviewer对HTTP仅静态阅读；门禁使用隔离archive+假mvn/XML实测，不等于实际Maven执行。

- API.md 把未实现会话列表、v1 会话详情/消息、tool-definition/dry-run 写成可调用；Workflow更新写成不存在的PATCH。
- API.md 泛称全写请求幂等键、默认cursor分页、UUIDv7/ULID，均不是当前实现。
- CURRENT_STATE 仍声称三类能力未绑定 Agent/Chat、流式故障矩阵未做；AGENTS/SPEC仍把已交付画布列为非目标。
- 管理页 Playwright `management.spec.ts` 使用 page.route mock，不能作为真实后端 CRUD 或数据库证据。

## 明确边界，不自动扩功能

- KnowledgeEmbedding 是确定性64维 hash bootstrap；本仓库无 hify-cc 的父子文档/RAG迭代评测交付，不能复用对方的 Recall 数值。
- 已有子任务协议不等于真实子 Agent 调度；无用户体系/鉴权/多租户，仍是受信网络产品。
- Provider/MCP DNS校验与连接解析间窗口、完整 MCP conformance、真实外部模型准确率/价格成本、部署恢复演练需要独立证据。
- Token明文不回传与加密存储不等于所有客户端/代理日志均安全；禁止录制真实Token浏览器trace或日志。
