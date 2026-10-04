# Workflow HTTP数字地址与响应类型补强

基线655adf3/计划205eb55，承接002独立review残余。IPv4严格四段十进制直接生成字节，原始URI与规范化host均校验；literal pinnedDns不调用resolver。IPv6只允许无zone的数字字符进入JDK解析，mapped在规范化之前拒绝。特殊用途范围未放宽。

窄测2026-10-04 22:54:59 CST：`mvn -B -pl hify-workflow -am -Dtest=WorkflowAddressPolicyTest,WorkflowHttpClientTest,WorkflowExternalPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`，17项（8+5+4）执行，failure/error/skip 0；首轮通过。fixture使用本机HttpServer，地址表测试不接触metadata或公网；非法数字地址的resolver计数0，合法字节地址与pinned结果相等；正常本机GET实际成功为正向对照。

HTTP响应测试覆盖UTF-8别名/大写、重复charset、两个Content-Type头、json-seq、GBK、未知编码、缺头、畸形头，拒绝时不返回正文。混合DNS测试改名mixedDnsRecords，不冒充真实攻击者变更DNS；另有resolver第二次返回metadata但调用次数仍为1的单次解析测试。

完整harness/backend/runtime、独立review待补，未部署132。未新增登录/ACL；未跑公网TLS实网、真实模型串联或网络级NAT64验证。002历史证据保持原样。
