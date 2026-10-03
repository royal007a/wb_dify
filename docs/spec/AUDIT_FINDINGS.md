# 审计发现（源码基线 973257c）

这是事实与待验证问题清单，不是任务状态板；实现状态看 harness/tasks.json。编号保留用于回归，不能以修改文档代替修复行为。

## P1：需先复现再修复

### A01 Knowledge 空命中/检索失败不形成显式缺口

`RunApplicationService.knowledgeContext` 捕获异常后继续、空 citations 返回空字符串。QueryLoop 得不到“知识证据不足”的结构化状态；`AgentApiIntegrationTest.publishesKnowledgeRevisionAndPreparesItBeforeQueryLoop` 只验证检索事件，不验证拒答/Claim grounding。这不能证明 SPEC 的“空命中不编造”。
要求：隔离 fake retrieval 返回空/抛错，断言不生成自称有依据的完成；有证据时必须保存可验证来源；无知识绑定普通聊天不能被误伤。产品选择严格拒答/受限回答的契约需清晰，不把 prompt 当代码门禁。

### A02 Workflow 执行缺取消与全局截止时间传播

`RunApplicationService.executeWorkflow` 只在执行前检查取消；`WorkflowEngine.execute(versionId,input)` 没有 cancellation/deadline 参数，只有50步上限。调用返回后可直接提交 COMPLETED，不经 QueryLoop FinishGate。TEMPLATE 快速通过的测试不能证明阻塞 KNOWLEDGE 节点取消可靠。
要求：阻塞检索节点的确定性测试；取消后不得启动下一节点/提交成功；截止时间包括检索且不因路径切换重置。记录失败/取消节点，不引入通用回滚。
控制链路修复`0ed58fe`/`40f90d2`/`3d867e7`：模块38项、HTTP/H2 7项通过，V21真实PG升级及既有PG回归零跳过通过。证据见`docs/evidence/SPEC_WORKFLOW_CONTROL.md`，保留首轮Docker磁盘不足失败。尚不证明实际JDBC阻塞中断；Workflow成功标准与知识证据门禁仍需A01/A03后续验收。

### A03 已发布 Workflow 的 KNOWLEDGE 节点读取可变知识库

`WorkflowEngine.executeNode` 使用 `knowledge.search(base,query,topK)`，未固定 corpus revision。现有冻结 Workflow checksum 只固定 DSL，不固定节点关联的语料。需验证“发布图→更新/归档知识→旧图执行”的行为，并明确资源快照与 schema checksum 两者不可混淆。

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

## 独立静态复核增量（0975782，待逐项红灯验证）

来源：mymacclaude 消息 `om_x100b63204ee2a8a0c2cced58d25ff1c`。对方明确本轮仅静态阅读；不能标成独立测试通过。后续提交需复查是否已消除相关路径。

- A1：MCP任意env/system引用会读取进程主密钥；本地检查发现Provider有同源路径。已用4个失败反例确认，70a5f33实现默认拒绝的引用/精确目标绑定，TOKEN换地址禁止KEEP；模块/HTTP与真实PG门禁通过，尚未部署，见SPEC_CREDENTIAL_BOUNDARY.md。
- A2/A3/A4/A5：对应既有A01/A03，另需修正Claim为空的FINISH语义、Workflow未走FinishGate、归档后canonical引用回读矛盾。
- B1/B2：取消/本地拒绝计入供应商熔断；runExecutor提交拒绝未收敛RUNNING。待故障注入验证。
- C1/C2/C3/C4：新Run创建期间取消旧Run、失效resume、网络结果不明时新幂等键重复创建、无gapIds的NEEDS_INPUT；另有重连计数和终态补读竞争。C4可达性尚未确认。
- D1/D2：图校验与50步执行上限不一致、条件表达式解析未在发布校验，字面量含运算符歧义。
- E1：线程池关闭中断可能误记用户取消而失去恢复；E-P2包括两张Run终态竞态、预取消重复事件、拒绝错误回显、取消标志被覆盖、跨实例取消非目标。事件相关先对照a1c2015复核。
- 其余P2：multipart错误500、主密钥错误启动未检测、停用Server对历史能力语义、LLM与索引共池、IllegalArgumentException回显、standalone MockMvc表述、非法旧DSL分类、图测试缺项及模板字面量边界。

以上是反例索引，不是完成状态；任务与验证进展仍只在Harness机器状态中记录。

F切片独立复核（0975782..fc7bd51）仍为静态阅读：`RunEventBroker` 锁内同步send/afterCommit可能被慢客户端阻塞；跨Run/未知Last-Event-ID需归属检查；取消抢赢时Workflow终态投影及两表一致性仍有缺口。事务保护断言是裸对象单测，生产代理的REQUIRED会自动开事务；受控afterCommit交错测试不是实际多线程竞态。PG序号唯一/回滚投影测试已跑，但不能据此宣称慢客户端隔离和全部重连边界已完成。

## 已核对的文档漂移

- API.md 把未实现会话列表、v1 会话详情/消息、tool-definition/dry-run 写成可调用；Workflow更新写成不存在的PATCH。
- API.md 泛称全写请求幂等键、默认cursor分页、UUIDv7/ULID，均不是当前实现。
- CURRENT_STATE 仍声称三类能力未绑定 Agent/Chat、流式故障矩阵未做；AGENTS/SPEC仍把已交付画布列为非目标。
- 管理页 Playwright `management.spec.ts` 使用 page.route mock，不能作为真实后端 CRUD 或数据库证据。

## 明确边界，不自动扩功能

- KnowledgeEmbedding 是确定性64维 hash bootstrap；本仓库无 hify-cc 的父子文档/RAG迭代评测交付，不能复用对方的 Recall 数值。
- 已有子任务协议不等于真实子 Agent 调度；无用户体系/鉴权/多租户，仍是受信网络产品。
- Provider/MCP DNS校验与连接解析间窗口、完整 MCP conformance、真实外部模型准确率/价格成本、部署恢复演练需要独立证据。
- Token明文不回传与加密存储不等于所有客户端/代理日志均安全；禁止录制真实Token浏览器trace或日志。
