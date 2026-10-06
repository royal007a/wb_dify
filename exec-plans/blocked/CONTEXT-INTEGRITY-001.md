# CONTEXT-INTEGRITY-001

授权：lark:om_x100b6372c9d534b0b177a6da50a9acc。用户要求实现、验证、部署；本任务完成H1与发布前验证，部署独立高风险任务。状态唯一真源tasks.json。

来源：前六讲研究最终报告（研究目录agent-course-first-six-20261006），Claude已独立复跑探针。基线898caa7，既有STRUCTURED首轮门禁失败保留。

## 契约与实现

- compactor不再根据内容关键词判断权威，不合成system摘要，不截断任何保留消息。
- 保留输入的全部system消息（顺序、原对象、内容不变）及最近user起的全部消息；不按固定两条消息切断工具批次。没有user边界则保留全部输入，避免猜测安全裁剪位置。
- 当前轮完整保留，旧非system轮可省略；原始canonical history及已存在的分层历史/归档引用不改。旧用户偏好并未因此自动成为持久高权限约束。
- ContextManager维持先memoryProjection、再archive、再compact、重测预算的顺序。完整保留部分装不下则沿用ContextWindowExceededException；QueryLoop须在模型调用前返回TOKEN_BUDGET_EXCEEDED。
- 信任边界限于输入已有role：本片不重新审计或认证上游已生成的system消息，不宣称防止所有提示注入。短工具文本未归档时也不升级角色；大工具结果仍先转引用。
- 无DB/API/DSL迁移，无新依赖。更保守的压缩可能更早拒绝过长当前轮，这是明确的正确性取舍。

## 步骤与验收

1. 在ContextManager/QueryLoop现有测试类补回归，先用旧实现跑红灯。
2. 最小修复compactor；测试无关键词策略、多system、旧工具指令不升级、当前多工具闭包、无user、超大策略拒绝、输入不变、模型零调用及合法正对照。
3. 更新测试清单、架构/安全文档；固定提交交叉review。
4. 独立Colima验证profile六scope门禁；若既有关停失败复现，先定位，禁止删除/放宽断言或用窄测顶替。
5. 发布独立预检磁盘/在途/备份/目标SHA并协调CC；空间不足不清共享数据，交用户决策。

## Review补强（8e855b0之后）

- 单轮多个中等工具结果的累计体积不能仅靠首轮字符阈值处理；压缩后仍超限时，从最早的工具结果开始逐个尝试归档，只有估算体积确实减少才采用，达到预算即停止。
- 额外归档不删消息、不改role或toolCallId，保留最新assistant工具调用批次及其所有结果。原有单条巨结果归档逻辑保持；该逻辑仍可能归档最新巨结果。
- 返回的归档引用保留原文，可对照canonical history；不声称history:// URI已是模型可直接调用的恢复API。
- 收窄局部防升权测试名，补manager无user超限和可变列表不修改断言。保留旧红灯，先跑长循环新负例再修复。

## 非目标

不同时实现报告H2-H5，不增写MCP、成本账本或动态工具路由；不操作CC/Dify/工作台，不改历史发布/证据。
