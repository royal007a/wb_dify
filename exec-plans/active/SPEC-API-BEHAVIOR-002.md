# 管理复核的契约来源与行为对照

基线a2ee546。只补测试与文档，不改变src/main或现有分页/取消HTTP兼容行为。

1. 将Workflow夹值与终态取消202注明为当前实现的兼容记录，而非原规格已定；KB非法分页400与Workflow夹值差异留待统一产品决策。
2. 现有取消集成测试补COMPLETED/FAILED两种终态夹具，真实HTTP handler取消后202、cancelRequestedAt仍null、整行和事件数不变。
3. 现有Agent解绑测试补真正创建旧会话，解绑并发布后从旧会话发Run，经生产异步执行器运行；核对Run旧Agent版本、workflow.started的版本/checksum、workflow_run详情中的版本/digest和最终正文。
4. 新key非法resume400反向对照已在83055fb补，连同新测试重跑；窄测与完整backend门禁分开记录。无产品修复，不能把新增断言通过写成新修复了缺陷。
5. 更新方法级证据和反例索引，交独立review。

范围：隔离H2/MockMvc和已有确定性Workflow/Mock模型，无真实密钥、无共享数据库/服务和132操作。
