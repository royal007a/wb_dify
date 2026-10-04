# Workflow HTTP数字地址与响应类型补强

基线655adf3/计划205eb55，承接002独立review残余。IPv4严格四段十进制直接生成字节，原始URI与规范化host均校验；literal pinnedDns不调用resolver。IPv6只允许无zone的数字字符进入JDK解析，mapped在规范化之前拒绝。特殊用途范围未放宽。

窄测2026-10-04 22:54:59 CST：`mvn -B -pl hify-workflow -am -Dtest=WorkflowAddressPolicyTest,WorkflowHttpClientTest,WorkflowExternalPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`，17项（8+5+4）执行，failure/error/skip 0；首轮通过。fixture使用本机HttpServer，地址表测试不接触metadata或公网；非法数字地址的resolver计数0，合法字节地址与pinned结果相等；正常本机GET实际成功为正向对照。

HTTP响应测试覆盖UTF-8别名/大写、重复charset、两个Content-Type头、json-seq、GBK、未知编码、缺头、畸形头，拒绝时不返回正文。混合DNS测试改名mixedDnsRecords，不冒充真实攻击者变更DNS；另有resolver第二次返回metadata但调用次数仍为1的单次解析测试。

完整harness/backend/runtime门禁于2026-10-04 15:04:31Z通过，被测de8e54a，invocation `98e3b5c3-d245-4f67-86ff-256221225bf7`。backend93类647项、runtime7类34项均全部执行且failure/error/skip/flaky为0；Harness74项通过。scope有重叠，不累加计数。证据目录 `harness/evidence/WORKFLOW-NODES-003/WORKFLOW-NODES-003-20261004T145552Z-195bbf8a/`，源码树和空diff见source-identity.json。

复算SHA256：verification `8a1fa8993416c1d6eaf8979526d2a3e982c0e47e49008b9a5d2220ab4ccac78e`，backend摘要 `54f9ab12cb7f1d2e8f06e2dc1e06b8d585832b4e5642f5ecd115c0a660476ade`，runtime摘要 `bd3eab1993f17bd05338d7127a6afd21ed70152bc692dd710eddb4ceb01e26a4`。

独立review在草稿目录用JDK17/OkHttp4.12片段、hosts探针和响应头表验证，未运行本仓Maven：原四条发现关闭，无P0/P1。残余归WORKFLOW-HTTP-004：IPv6内部helper的宽松字符集仍可使不可达的畸形字符串进入系统解析器，故不能将“合法HTTP入口只解析一次”外推成任意内部输入均不DNS；规范化后第二次host检查缺专门突变对照；charset*扩展参数不影响严格UTF-8解码但未拒绝。IPv4严格字节解析不受此限制。

未部署132。未新增登录/ACL；未跑公网TLS实网、真实模型串联或网络级NAT64验证。002历史证据保持原样。
