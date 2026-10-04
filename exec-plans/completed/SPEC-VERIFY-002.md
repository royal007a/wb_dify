# SPEC-VERIFY-002：阻塞时提交与报告状态

基线3455d52；已部署应用不改，仅测试、报告器与脱敏证据。

1. 慢发送entered门闩之后，再分别发布同run/同hash另一run事件并执行afterCommit，断言有限时间返回，保留原订阅/心跳隔离断言。
2. 路由映射子场景fail/not-run时显示partial；无映射仍not-run；全部pass仅mapped-subcases-only，不升级为整接口通过。构造缺方法/失败的报告器测试先红后绿。
3. 提供只含方法选择器和状态的脱敏portable输入，绑定本轮invocation/summary SHA，禁止复制XML properties、失败正文和stdout。离线输入生成同样报告；该输入非签名，不能防恶意仓库写入者。
4. 窄测、harness/backend全量门禁、方法报告重算；不重新部署（无生产源代码变化）。独立review核对断言强度和报告口径。
