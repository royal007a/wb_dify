# WORKFLOW-NODES-001 验收记录

基线 538b510，契约见 `../spec/SPEC_WORKFLOW_EXTERNAL_NODES.md`。生产实现新增 LLM/API_CALL（GET），没有迁移、任意脚本或写方法。旧图仍用相同校验/取消/终态机制。

开发过程：第一轮 compile 因 WorkflowDefinitionException 缺 import 失败，已修复；第一轮 fixture 集成7项通过，扩展后10项通过。含 NativeProviderModelClientTest 7、WorkflowControlTest 11、WorkflowGraphValidatorTest 28、WorkflowEngineTest 14，总计70项窄测（包含10项app集成），均零失败/错误/skip。后续新增DNS私网与错误凭据的负例，以最终门禁计数为准。

尚待：完整 harness/backend/runtime/frontend 门禁、真实本地模型串联、浏览器结果与独立复核。未部署132，未宣称当前任务完成。

边界：HTTP fixture只证明协议/准入，不证明远端业务语义；真实小模型只证明串联可执行，不是质量benchmark。没有浏览器全流程故障矩阵，没有供应商网络层总响应字节硬限额（LLM输出为交付限制）。系统DNS解析回收、共享Provider熔断/重试、整个Workflow恢复重跑、无应用登录仍按开放边界处理。
