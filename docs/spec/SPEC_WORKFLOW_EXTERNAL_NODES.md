# Workflow LLM 与受控 GET 节点

任务 WORKFLOW-NODES-001；既有图和条件规则不变，无新增迁移。LLM/API_CALL 都沿用唯一发布版本、必经上游引用、最长路径50步、Workflow总期限和取消/关闭信号。不是任意脚本、写工具、并行 DAG 或 Agent Loop。

## LLM

配置：providerId、modelId、prompt（可引用必经上游）、可选 systemPrompt、temperature（0..2，默认0.2）、maxOutputTokens（1..4096，默认1024）、outputVariable（默认result）。展开后 system+prompt 至多16000个UTF-16单元；纯文本输出至多32768 UTF-8字节。输入预算在模型调用之前检查，供应商输出 token 参数按协议映射；本地字节上限是收到响应后的交付限制，不是供应商计费或网络读取硬上限。

发布时服务端写 modelSnapshot 和顶层 externalNodeFormat，原始DSL/checksum包含它们；草稿不能指定modelSnapshot。执行固定Provider类型、URL、鉴权引用和模型，不随草稿配置变动；Provider/模型的运行时停用仍有效。凭据授权每次解析，未保存明文；供应商同名模型权重变化不受快照控制。单节点最长45秒且不超过原Run期限；不提供tools，返回tool_calls拒绝。输出不做stream delta交付，不自动标成VERIFIED。原有供应商熔断/重试共用，不能声称整个Workflow或外部调用恰好一次。

## API_CALL

配置：method只能GET（默认GET）、endpoint为静态完整HTTP(S) URL、可选credentialRef、query文本对象（至多16项，键128字符、展开值2048字符以内）、outputVariable（默认result）。仅query值可做模板插值，使用URL参数编码；端点、凭据、Provider标识不可插值。禁止任意headers/token字段，鉴权只支持已授权凭据引用的Bearer。

管理员环境变量 `HIFY_WORKFLOW_HTTP_ALLOWED_ENDPOINTS` 是JSON字符串数组，默认 `[]`：例如 `["https://example.com/api/read"]`。按规范化后的完整scheme/host/port/path精确匹配，不含query，不支持通配符；允许的GET必须由管理员确认无写副作用。credentialRef还须在 `HIFY_CREDENTIAL_REFERENCE_BINDINGS` 对该endpoint单独授权。发布和执行时都检查授权，撤销后拒绝执行。

不使用系统代理，不跟随HTTP/HTTPS重定向，不自动重试。对hostname的连接DNS解析结果检查私网/回环/链路本地/IPv6 ULA并直接用于该次连接，不进行“校验后再次解析”；私网literal IP必须明确出现在运营者授权中。此能力不会让普通API调用者设置授权。connect3秒、read5秒、call10秒并受剩余Run期限限制，复用有界workflowIoExecutor；取消时cancel网络call。系统DNS阻塞不保证立即回收操作系统解析线程。

响应仅接受2xx、有效UTF-8且无NUL，读最多32769字节，超过32KiB拒绝；不持久化原始异常/错误响应。识别到当前鉴权值原样回显则拒绝，不宣称通用秘密检测。HTTP结果与LLM结果都属于不可信内容，可进入后续LLM输入，但不能变成可信系统指令或授权来源。

## 发布、恢复与验收边界

旧无外部节点的图兼容；旧的客户端伪造LLM配置没有服务端publication标记时拒绝，先重新发布Workflow，再发布Agent并新建会话。恢复仍会重跑整个Workflow，不保证外部调用/付费生成恰好一次。KNOWLEDGE节点的来源约束不等于LLM回答语义核验，Workflow输出没有复用Chat知识FinishGate，界面不得写“答案已核验”。

测试须覆盖合法图的执行行正向增量、固定模型、停用、伪造快照、预算、工具调用拒绝、GET参数编码、越权端点、重定向、超大/凭据回显响应、调用前及在途取消/期限。窄测使用本地HttpServer协议fixture；真实本地模型验收和部署单列，不以fixture证明模型质量。没有在本片引入应用登录/ACL。
