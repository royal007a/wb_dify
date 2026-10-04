# Workflow LLM 与受控 GET 节点

任务 WORKFLOW-NODES-001；既有图和条件规则不变，无新增迁移。LLM/API_CALL 都沿用唯一发布版本、必经上游引用、最长路径50步、Workflow总期限和取消/关闭信号。不是任意脚本、写工具、并行 DAG 或 Agent Loop。

## LLM

配置：providerId、modelId、prompt（可引用必经上游）、可选 systemPrompt、temperature（0..2，默认0.2）、maxOutputTokens（1..4096，默认1024）、outputVariable（默认result）。展开后 system+prompt 至多16000个UTF-16单元；纯文本输出至多32768 UTF-8字节。输入预算在模型调用之前检查，供应商输出 token 参数按协议映射；本地字节上限是收到响应后的交付限制，不是供应商计费或网络读取硬上限。

发布时服务端写 modelSnapshot 和顶层 externalNodeFormat，原始DSL/checksum包含它们；草稿不能指定modelSnapshot。执行固定Provider类型、URL、鉴权引用和模型，不随草稿配置变动；Provider/模型的运行时停用仍有效。凭据授权每次解析，未保存明文；供应商同名模型权重变化不受快照控制。单节点最长45秒且不超过原Run期限；不提供tools，返回tool_calls拒绝。输出不做stream delta交付，不自动标成VERIFIED。原有供应商熔断/重试共用，不能声称整个Workflow或外部调用恰好一次。

## API_CALL

配置：method只能GET（默认GET）、endpoint为静态完整HTTP(S) URL、可选credentialRef、query文本对象（至多16项，键128字符、展开值2048字符以内）、outputVariable（默认result）。仅query值可做模板插值，使用URL参数编码；端点、凭据、Provider标识不可插值。禁止任意headers/token字段，鉴权只支持已授权凭据引用的Bearer。

管理员环境变量 `HIFY_WORKFLOW_HTTP_ALLOWED_ENDPOINTS` 是JSON字符串数组，默认 `[]`：例如 `["https://example.com/api/read"]`。按规范化后的完整scheme/host/port/path精确匹配，不含query，不支持通配符；允许的GET必须由管理员确认无写副作用。credentialRef还须在 `HIFY_CREDENTIAL_REFERENCE_BINDINGS` 对该endpoint单独授权。发布和执行时都检查授权，撤销后拒绝执行。

不使用系统代理，不跟随HTTP/HTTPS重定向，不自动重试。对hostname的所有A/AAAA结果进行检查，任一不安全则整组拒绝；通过后的地址集合只用于该次call并固定，不进行“校验后再次解析”。URL仍保留原主机名，TLS SNI与证书主机名校验不改成IP，也不关闭校验。

WORKFLOW-NODES-002 的保守地址策略同时作用于域名解析和literal IP：IPv4拒绝0/8、100.64/10（含常见云metadata地址）、169.254/16、192.0.0/24、192.0.2/24、192.88.99/24、198.18/15、198.51.100/24、203.0.113/24、224/4及240/4；IPv6仅接受原生2000::/3公网单播，另外拒绝2001::/23、2001:db8::/32、2002::/16与3fff::/20。因此NAT64、IPv4-compatible/mapped、ULA、link-local与multicast均不属于公网允许范围。IPv4-mapped的literal表示在URL规范化前拒绝，防止Java将它变为IPv4而丢失来源。

仅有显式列入精确endpoint白名单的RFC1918、IPv4回环及IPv6 `::1` literal允许访问内网/本机；域名即使解析到这些地址仍拒绝。metadata/link-local/CGNAT不能靠literal授权绕过。这是有意保留的管理员内网GET能力，不是默认开放内网。特殊用途网段采用保守拒绝，某些IANA可公网的协议特例也不会放行；依据为[IANA IPv4登记表](https://www.iana.org/assignments/iana-ipv4-special-registry/)、[IPv6登记表](https://www.iana.org/assignments/iana-ipv6-special-registry/)（2026-10-04核对）。不声称识别所有运营商自定义路由或网络级透明代理。

此能力不会让普通API调用者设置授权。connect3秒、read5秒、call10秒并受剩余Run期限限制，复用有界workflowIoExecutor；取消时cancel网络call。系统DNS阻塞不保证立即回收操作系统解析线程。HTTP套接字/节点超时或LLM的45秒节点预算用尽，父Run尚未到期时记为节点FAILED/WORKFLOW_ERROR；只有原Run期限已到才TIMED_OUT/TIMEOUT。取消与关闭仍先按原Run控制处理。

响应仅接受2xx，Content-Type必须为text/plain、application/json或application/*+json；声明charset时必须UTF-8，正文始终按严格UTF-8解码且禁止NUL。不要求JSON类型正文具备某个业务schema，不做浏览器HTML解析。读最多32769字节（包括chunked和自动解压后的正文），正好32768允许、超过32KiB拒绝，不交付截断片段；不持久化原始异常/错误响应。识别到当前鉴权值原样回显则拒绝，不宣称通用秘密检测。HTTP结果与LLM结果都属于不可信内容，可进入后续LLM输入，但不能变成可信系统指令或授权来源。

## 发布、恢复与验收边界

旧无外部节点的图兼容；旧的客户端伪造LLM配置没有服务端publication标记时拒绝，先重新发布Workflow，再发布Agent并新建会话。恢复仍会重跑整个Workflow，不保证外部调用/付费生成恰好一次。KNOWLEDGE节点的来源约束不等于LLM回答语义核验，Workflow输出没有复用Chat知识FinishGate，界面不得写“答案已核验”。

测试须覆盖合法图的执行行正向增量、固定模型、停用、伪造快照、预算、工具调用拒绝、GET参数编码、越权端点、重定向、超大/凭据回显响应、调用前及在途取消/期限。窄测使用本地HttpServer协议fixture；真实本地模型验收和部署单列，不以fixture证明模型质量。没有在本片引入应用登录/ACL。
