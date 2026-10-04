# 完成证据目录、日志和计数补强

基线9604ba3，针对AUDIT-004独立复核四个实测P2和一个静态状态篡改边界。只改工程Harness，不改产品、共享Docker或部署。验收scope保持harness。

1. 先增加反例：同根run目录符号链接、错用run.json/复用步骤日志、重算SHA但classes与totals矛盾/空计数/零执行/遗漏期望类、扩充legacy清单。拒绝失败必须发生在完成状态写入之前，合法CLI和便携校验为正向对照。
2. run目录必须是规范harness/evidence/taskId/runId且无路径重定向；各步只能使用该目录下自己的step.log。manifest和摘要继续逐文件校验，不允许通过相同字节绕过归属。
3. 完成时读取expected-maven-suites.json并对照既有summary.expectedSuites/inventorySha256。按classes重算所有非负整数totals，要求tests/executed>0、零失败/skip/flake、逐类满足最少数。validate历史回读使用已摘要绑定的当次期望集合，不能因当前用例数增长否定旧证据；精确legacy前缀仍不追认通过。
4. 在执行代码固定现有legacy文件SHA256（不改清单）；单独改清单不能扩展兼容。测试只在隔离fixture里替换pin，生产CLI不增加绕过开关。可同时改执行代码的恶意写者仍不在防御范围，不称签名。
5. 实测手改任务状态场景，分清有效历史证据与新run完成；无独立可信存储不能证明tasks文件未被任意回滚，不虚构保证。
6. 提交代码后run-task执行完整harness门禁，保留反例与计数摘要；git archive验证便携模式，发mymacclaude只读复核。原始日志/XML不提交，未运行Maven/PG/132。
