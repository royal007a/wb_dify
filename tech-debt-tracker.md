# Hify Technical Debt

技术债不是任务状态；进入执行时必须在 `harness/tasks.json` 建原子任务并引用本条目。

| ID | 影响 | 优先级 | 触发条件 | 当前处理 |
|---|---|---|---|---|
| TD-001 | Intent v2 在同一开发集调参与报告，不能作为无偏上线指标 | P0 | Intent Router 准备 shadow/接管 Run | `EVAL-001` |
| TD-002 | 前端主 chunk 超过 1 MB，首屏缓存和更新成本偏高 | P2 | 实测首屏或弱网指标不达标 | 触发后做路由级拆包 |
| TD-003 | JPA/MyBatis-Plus 双持久层增加维护认知 | P2 | 某模块发生受其影响的实质改造 | 受测试保护地逐模块迁移，禁止全仓重写 |
| TD-004 | 真实 Provider 流式故障矩阵不完整 | P1 | 真实 Provider 上线前 | `PROVIDER-001` |
