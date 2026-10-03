# 知识读取的关闭边界

仅修正来源入场 catch 的异常分类，不改变引用协议、数据库和页面。先用真实 Run 编排、可控检索异常和生命周期信号证明会被永久判失败，再保留关闭挂起语义。最终门禁覆盖新旧知识用例、RunShutdownIntegrationTest 与 runtime。声明模拟的是信号分类，不把假生命周期测试说成真实进程销毁顺序；生产关闭顺序证据沿用 E1。

验收：显式 Suspended 或 stopping 期间检索异常保留 RUNNING、有 run.interrupted，模型零调用，不提交知识失败终态；未关闭时异常仍 KNOWLEDGE_RETRIEVAL_FAILED；全部相关回归通过。无部署/凭据/生产库。回退仅代码；历史事件不回滚。
