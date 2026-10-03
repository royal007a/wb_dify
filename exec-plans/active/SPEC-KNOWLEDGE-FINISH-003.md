# Knowledge memory delivery boundary

- 只改 ~/hify；不碰 hify-cc、共享服务或凭据。
- canonical history 必须在核验前提交以支持重放，不将其误当已交付文本。
- 对有知识绑定或 checkpoint 知识门禁的 Run 隔离全部原始 memory 投影（含旧目录、摘要、搜索和详情）。完成回答继续由既有 Run/chat_messages 交付；普通聊天兼容。不删除旧历史或索引。
- 来源 Run 的门禁决定同会话 recall 可见性，不能用无知识绑定的调用方绕过。
- 先加 RUNNING / NEEDS_INPUT / 已有索引读取反例，再修；补 canonical 回读中 stopping 的集成测试（不是实际进程销毁顺序证明）。
- H2 定向、真实 PG、runtime 和 harness 门禁；保存红绿证据、独立 commit、交独立复核。
- 不宣称语义蕴含验证；基础设施故障分类、核验 UI 和检索事件口径另登记。
