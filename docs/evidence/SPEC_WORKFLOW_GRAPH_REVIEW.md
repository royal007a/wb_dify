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

## 最终门禁（2026-10-04 06:59 CST）

源码0358827，测试/契约39c9e52。runner定向命令50项，runtime34项，均0失败/错误/跳过；harness状态、规格、5项单测、Shell语法以及frontend typecheck/build全部退出0。构建仍提示既有主包大于500KB，非失败。本原子任务completed，独立复核待回，不代表67接口全验或生产部署完成。

## 独立静态复核

mymacclaude复核55423ca..1f21997，未构建/跑测试；确认原D两P1关闭，无新P0/P1。新P2归入SPEC-WORKFLOW-GRAPH-003：全角空白与trim不一致、旧反斜杠字面量语义兼容、旧裸标点关键词迁移说明/检查、节点级错误提示、started/failed投影与列表可用性口径。关键词含模板定界符的既有约束继续明确，不把静态通过当成已部署。
