# Workflow 外部节点安全与超时补强

任务 WORKFLOW-NODES-002，基线 aec943e，被测代码37ecb42。完整backend/harness/runtime门禁于2026-10-04T14:47:31Z通过；未部署132。

证据目录：`harness/evidence/WORKFLOW-NODES-002/WORKFLOW-NODES-002-20261004T143551Z-b8d270af/`。schema3 verification passed，invocationId=2688413f-ca7b-4490-b43e-007d3bcadb3b；backend 93类644项、runtime 7类34项均全部执行，failures/errors/skipped/flakyAttempts均0，scope有重叠不相加。Harness Python 74项通过。真实PG使用独立colima-hify-verify-20261004，不碰Dify/default profile。源码树和空diff见source-identity.json；原始日志本地保留但gitignore，已提交逐类摘要及SHA，不称离线复算过原始日志。

独立review：mymacclaude静态阅读及JDK17/OkHttp4.12离线地址片段确认旧特殊网段P1关闭、没有P0/P1，未运行项目测试或访问服务。剩余数字IPv4前导零/非标准写法、charset参数与回归测试缺口另行补强；002证据不覆盖这些后续修改。DNS混合记录测试不等于真实DNS rebinding演练，单次解析由受控resolver计数证明。

本片补强：所有DNS地址统一准入且每次call固定地址集合；特殊用途/元数据网段也不能通过literal精确授权绕过；仅保留显式RFC1918/loopback literal的内网GET能力。Content-Type和UTF-8声明校验；HTTP网络超时/LLM节点预算不再直接冒充父Run到期。

开发窄测第一轮26项通过，随后补了一条使用生产5秒read timeout的完整Workflow终态负例；最终窄测于22:34:55 CST以exit0完成27项（6地址策略、4HTTP客户端、4节点配置/预算、13app集成），failure/error/skip均0。命令：`mvn -B -pl hify-app -am -Dtest=WorkflowAddressPolicyTest,WorkflowHttpClientTest,WorkflowExternalPolicyTest,WorkflowExternalNodesIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。这不是PG测试或真实模型验证。

测试设计：纯地址表与假DNS测试不访问公网或metadata；本机HttpServer分别返回Content-Length和chunked的32768/32769边界、错误类型、停滞响应；Provider URL变更测试先驱逐缓存并读到新配置，之后旧发布图仍调用旧endpoint；模型停用负例；凭据绑定撤销用同一已冻结图加新策略实例模拟管理员配置重载。LLM短预算使用测试专属构造器，不改变生产45秒上限。

边界：未新增ACL、未防止Provider删除再以相同publicId创建、未给Workflow叠加Chat知识FinishGate；供应商max_tokens兼容性和编码后秘密回显仍是开放项。显式私网GET需管理员确认无副作用，不能替代网络层隔离。真实模型GET→LLM抽测、TLS实网及发布证据与fixture分开记录。
