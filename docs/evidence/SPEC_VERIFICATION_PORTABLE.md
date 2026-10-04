# SPEC-VERIFY-002：阻塞中再次提交与可复算报告

## 范围

只改测试、报告器和证据，不改线上实现，不重新部署132。

- 背压用例在 `send` 的 entered 门闩之后，保持 release 未释放，再分别执行同 Run、同 hash 另一 Run 的 publish + afterCommit，200ms 内返回，并校验两个 Run 的实际写事件次数；保留原订阅、心跳和握手前不查库断言。这是受控 mock 运输层测试，不替代真实 TCP/数据库连接的网络隔离测试。
- 路由有映射且任一子场景 fail/not-run 时显示 partial；全部通过仅显示 mapped-subcases-only，不声称整路由或功能组合穷举。
- 显式 `--export-methods` 输出方法选择器和状态数组、XML SHA 清单、invocationId、summary SHA。没有 properties、stdout、失败正文。显式 `--method-evidence` 才读取该输入；不会静默回退旧文件。XML 路径要求摘要中的每个类恰好出现一次，并拒绝简单类名歧义。

## 验证方法

报告器新增两项反例在旧实现下失败（缺少 route_status/portable_input），保留 red.log。随后增加完整 build 离线对照：先从合成 XML 导出输入，删除测试临时 XML 后重算报告完全相等；改名的方法使子场景 not-run、路由 partial；原始属性与 stdout 的标记不出现在输入中。真实门禁生成报告之后，再使用已提交的脱敏输入逐字节比对 JSON 和 Markdown。

这些 SHA 绑定用于发现误拿旧证据和意外变化，不是签名；有仓库写权限的人仍可以同时伪造输入和记录。不能把脱敏输入当成重新执行测试。

## 本轮结果（2026-10-04）

代码 `b0e3882`，门禁于02:14:13Z结束，schema3、backend/harness均passed。后端81类547项全部执行，failures/errors/skipped/flaky均0；背压类3项，最少数量满足。Harness54项通过，其中报告器9项；窄测同样通过。这次未重跑前端/浏览器、独立migration scope、真实供应商或132；没有修改src/main、frontend或deploy。

证据目录：`harness/evidence/SPEC-VERIFY-002/SPEC-VERIFY-002-20261004T020317Z-18c9e471/`。source-identity记录代码树及空源码diff；reproduction.json记录原始窄测日志SHA、脱敏输入SHA、两次命令与输出SHA。

从本次与摘要匹配的XML导出method-evidence.json后，再显式以它重算，behavior-report.json和Markdown逐字节一致：68条路由、38组功能，36个具名子场景pass、0 fail/not-run。路由仍为mapped-subcases-only，不是68条接口所有行为通过。JSON SHA `b6fc59cc…`，Markdown `1503825b…`，脱敏输入 `ce101ea1…`；完整值在reproduction.json。

只需仓库已提交文件的复现命令：

```sh
python3 harness/behavior_report.py \
  --evidence-dir harness/evidence/SPEC-VERIFY-002/SPEC-VERIFY-002-20261004T020317Z-18c9e471 \
  --method-evidence harness/evidence/SPEC-VERIFY-002/SPEC-VERIFY-002-20261004T020317Z-18c9e471/method-evidence.json
```

独立审查方在b0e3882隔离archive里实跑报告器9项；背压仅静态复核。其提出的P3（observed未纳入verification本身摘要）继续作为证据边界：本次reproduction另记整个输入SHA，但没有改动已完成的门禁manifest，也不声称自动门禁验证该SHA。
