# 完成证据实文件与冻结历史准入

任务SPEC-AUDIT-004，基线ebee870/计划ba4e63c。仅Harness、测试、规格；未改应用src/main、frontend或deploy，不启动Maven/Docker/业务库/132。

## 修复对应

- 原P2-A：finish不再只信manifest里的哈希字符串。真实日志、Maven摘要都限定本run目录，复算SHA并核对摘要result、invocation、step、exit和整个tests对象。缺失/篡改/越界拒绝，正常文件有正向完成对照。
- 原P2-B：已有86条记录按规范JSON摘要冻结；兼容只认精确的历史前缀，不以schema缺失/降级推断旧制。遍历每条completed证据，检查verification SHA；空证据、假历史、blocked尾掩盖完成均拒绝。
- finish强制实文件；validate支持不含gitignore原始日志的提交副本，但新Maven摘要不得缺，存在的日志不得不匹配，输出明确是portable metadata检查。这个边界写进规格和QUALITY，不将缺失日志的哈希说成已复算。
- 不改写历史verification，不把历史partial/skip当通过；清单不是签名。恶意写者同时重造仓库、日志、摘要仍不在信任边界内。

## 开发阶段证据

目录：`harness/evidence/SPEC-AUDIT-004/SPEC-AUDIT-004-20261004T040826Z-fb3aedc0/`。仅提交去正文的结构化摘要及SHA，不提交原始日志。

- 首轮14项状态测试在旧harness上为12个失败断言、2个错误；错误是旧finish已错误完成任务后同一用例继续操作产生的级联，不能当作14个独立反例。
- 另用当前具名用例、内存加载`git show ba4e63c:harness/harness.py`，独立复现缺日志仍finish、完成后摘要改partial未检出、冻结历史被改仍通过；不调用外部服务，结果见`old-code-counterexamples.log`及摘要。
- 命令行补测最初把拒绝退出码误写为1，而CLI的ValueError约定是2。只纠正测试的预期退出码，仍断言runner是blocked、有失败证据、不允许completed，没有修改退出行为。
- 首轮完整Python回归64项有1个失败：未修改的DeployInstallerTest在dash/HUP分支等待5秒后未到达门闩。失败保留于`python-green.log`（文件名不代表结果）；该具名测试不改代码独立重跑通过（26.836秒、包含两种shell/三个信号）。这提示固定等待窗仍有调度敏感性，不称已消除flakiness，也不将窄重跑冒充整轮通过。

## 本次完整Harness gate

代码cee737f；上述run于2026-10-04 04:27:20Z生成schema v3 verification，scope仅harness，五个步骤均exit 0、result/commandResult=passed。完整Python64项通过（115.535秒），包括前述未修改的部署信号测试；首次失败不被覆盖或回溯标绿。Maven未运行，所以testCoverage=not-assessed，Python计数单独写入`gate-summary.json`而不是冒充Maven摘要。

finish在五份日志仍存在时复算并完成；完成后的validate/check-progress也通过。gate-summary记录五份实际日志全部重新计算且一致，三个实现/测试/清单文件SHA与git blob，以及相对测试HEAD的空diff SHA。本次不改backend/frontend/deploy。独立review仍待结论。

另把证据提交ef4b1e3用`git archive`导出到新临时目录，运行`harness.py --root <副本> validate`和`check-progress`均退出0；该副本没有本任务的任何*.log。此项实际验证便携元数据回读，不是重新运行测试，未复算不存在的原始日志。完成时缺日志必须拒绝仍由具名用例独立验证。

不改变SPEC-KNOWLEDGE-INTEGRITY-003因Colima磁盘满未通过完整PG门禁的事实。任务状态只看tasks.json。
