# SPEC-VERIFY-002：阻塞中再次提交与可复算报告

## 范围

只改测试、报告器和证据，不改线上实现，不重新部署132。

- 背压用例在 `send` 的 entered 门闩之后，保持 release 未释放，再分别执行同 Run、同 hash 另一 Run 的 publish + afterCommit，200ms 内返回，并校验两个 Run 的实际写事件次数；保留原订阅、心跳和握手前不查库断言。这是受控 mock 运输层测试，不替代真实 TCP/数据库连接的网络隔离测试。
- 路由有映射且任一子场景 fail/not-run 时显示 partial；全部通过仅显示 mapped-subcases-only，不声称整路由或功能组合穷举。
- 显式 `--export-methods` 输出方法选择器和状态数组、XML SHA 清单、invocationId、summary SHA。没有 properties、stdout、失败正文。显式 `--method-evidence` 才读取该输入；不会静默回退旧文件。XML 路径要求摘要中的每个类恰好出现一次，并拒绝简单类名歧义。

## 验证方法

报告器新增两项反例在旧实现下失败（缺少 route_status/portable_input），保留 red.log。随后增加完整 build 离线对照：先从合成 XML 导出输入，删除测试临时 XML 后重算报告完全相等；改名的方法使子场景 not-run、路由 partial；原始属性与 stdout 的标记不出现在输入中。真实门禁生成报告之后，再使用已提交的脱敏输入逐字节比对 JSON 和 Markdown。

这些 SHA 绑定用于发现误拿旧证据和意外变化，不是签名；有仓库写权限的人仍可以同时伪造输入和记录。不能把脱敏输入当成重新执行测试。

本轮完整门禁和具体重算命令见本任务 evidence 目录及后续记录；未完成前不以本段宣称通过。
