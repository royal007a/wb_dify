# 完成门禁第二轮补强（SPEC-AUDIT-005）

计划65775d5；代码0fee0df；证据目录`harness/evidence/SPEC-AUDIT-005/SPEC-AUDIT-005-20261004T045714Z-43f72aa9/`。仅工程Harness，不改backend/frontend/deploy，不跑真实Maven、Docker或132。

## 已实现

1. run目录精确绑定规范task/runId路径，拒绝同根另一run符号链接；仓库根自身的系统别名先归一化。
2. 每个步骤只接受该run中的step.log；run.json、另一step的日志或指向另一step的符号链接，即使重算SHA也不能冒充。
3. 新完成时对照当前expected-maven-suites清单及SHA；逐类校验非负整数、最少数、集合、实际执行，重算全部totals。历史便携回读用摘要绑定的当次清单，不因新测试增长改写旧结论。
4. 现有legacy清单字节SHA固定在代码，文件保持不变，未增加生产绕过开关。测试仅在临时fixture中替换pin以隔离合成数据。

第五项未谎称修复：任意仓库写者可将旧的有效completed状态改pending再改回来，validate无法认证中间的人工修改不存在；实测不会新增运行记录，也不能在idle调用finish。新run运行中只改任务状态则会与currentTaskId矛盾而被拒。仍不防同时修改执行代码与所有证据，不是签名。

## 实际执行与失败保留

- 首轮新增反例在旧代码下：25项测试，20个失败断言；包括参数化子场景，不能称20个独立缺陷。
- 首版实现：25项中13个错误，原因是macOS临时根/var与/private/var别名比较。首条进度曾误称通过，下一条立即更正；失败日志保留。修正根归一化后25项通过。
- 最终29项同一份测试在65775d5的git archive隔离副本上执行：25个失败断言、0 error；新代码29项通过。四项实际CLI负例分别覆盖目录、日志、重签摘要隐藏skip、扩充legacy；恢复合法输入后的CLI完成及缺日志便携validate为正向对照。
- 完整harness门禁：**74项Python通过**（29项状态机测试包含在内，不能另加），65.079秒；state、progress、API生成、Python、shell语法五步全部exit0。verification schema3、strictEvidence、head=0fee0df，结果passed；testCoverage=not-assessed，因没有Maven范围。
- 另解析已提交的INPUT-HYGIENE-003 585项摘要，确认真实摘要格式与新计数校验兼容；不是重跑585项。

`regression-summary.json`保留所有轮次和源码SHA；`gate-summary.json`记录manifest SHA、各日志SHA及代码diff身份。本机逐项重算与verification一致。原始日志不提交；摘要不是签名，git archive不能复算被忽略的日志。

旧代码对照的临时archive已删除，可从65775d5重新导出并复制最终test_harness.py复现；没有删除任何用户数据、共享Docker资源或发布目录。完整产品门禁和部署阻塞不因本次harness通过而消失。
