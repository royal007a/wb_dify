# 验证门禁闭合

1. 用隔离fixtures重现Flakes漏计、预期类缺失、stdout伪装、finish无验证仍完成。
2. Maven每步使用全新报告目录，预期类由版本化清单约束；XML逐用例与suite计数一致、失败/错误/skip/flake均不放行，历史日志摘要只作历史观察。
3. finish对当前run、scopes、HEAD、结果做检查；validate重查新制已完成记录；历史记录加明确例外说明而不重写旧证据。
4. 修正文档skip事件数和Docker测试证据等级，统一未关闭问题任务归属，登记验收项。
5. Python隔离测试、真实窄Maven和harness scope分别运行；不触碰共享服务、hify-cc、132或真实凭据。完成后交独立只读复验，再恢复SPEC-VERIFY-001。
