# Workflow 长路径与条件解析复验

任务 SPEC-WORKFLOW-GRAPH-002，基线 55423ca。范围仅 ~/hify；不修改课程仓库、共享服务、真实凭据或数据库历史。

证据目录：`harness/evidence/SPEC-WORKFLOW-GRAPH-002/SPEC-WORKFLOW-GRAPH-002-20261003T225202Z-ce8fc33a/`。

## 失败先行

`red-graph.log`：原实现运行 WorkflowGraphValidatorTest、WorkflowEngineTest，共 37 项，9 失败、0 错误/跳过。长链51步仍被接受，6类非法条件没有在保存前拒绝，含 contains 的等值字面量被错解，旧裸 hello 在节点执行后才失败。

修复最长结构路径上限与引号感知解析后，`green-graph.log` 同37项全部通过。总节点数97、最长路径50的宽图仍允许，避免用全图节点数误伤分支。

## 扩展验证

`green-api.log`：模块41项（validator28 / engine9 / expression4）与HTTP集成9项（API5 / Boundary3 / Control1）全部通过、0跳过。Boundary通过真实Spring请求链验证50步执行、51步创建/更新拒绝、旧草稿发布拒绝、引号关键字比较、旧非法版本绑定/执行拒绝以及Chat的WORKFLOW_ERROR终态。旧版本夹具通过测试库直接写入checksum合法的历史DSL，未对任何共享数据执行此操作。

增强unicode转义引用的反例和正向对照（开/闭括号全部编码），`green-final.log` 同50项通过。该正向对照证明不是被原始文本的未闭合模板检查提前拦住；解码后的未知上游必须被拒。

生产修改：最长路径与运行守卫共用50常量；表达式解析在插值前完成，解码后的引用仍走必经检查；图异常在Chat归类WORKFLOW_ERROR。前端新建关键词用JSON.stringify生成字面量。

契约：`docs/spec/SPEC_WORKFLOW_GRAPH.md`。保留纯文本比较，不增加脚本/布尔组合；旧歧义条件需要重新编辑发布。此片不处理所有Workflow数据库异常归类，也不增加节点调度能力。

## 验证边界

H2隔离测试、不调用付费模型；无新迁移。本片前端仅类型检查/生产构建，不宣称浏览器实测；尚未部署。最终runner的命令、源码HEAD、runtime/frontend/harness门禁以verification.json为准，完成后补记摘要。
