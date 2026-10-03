# 规格复核补证据等级与测试摘要

任务SPEC-AUDIT-002，基线e43369b；原版Hify，不涉及hify-cc或132。任务状态只在Harness。

## 文档修正

- CURRENT_STATE去掉Agent Console、lost收敛、CAS、能力绑定、PG的无条件“已验证/完成”；区分源码、专项测试、历史部署。本轮11个PG类跳过就地说明。
- FUNCTION_TESTS标明F15受控关闭时序/非fork JVM、本轮PG跳过，F38仅接口库存；追加400/404/405/409/413/415/503、取消幂等、执行器拒绝202/重放200、lookup只读/no-store负路径要求，不声称都已测。
- AUDIT_FINDINGS区分0975782旧反例与后来修复，补具体未关闭索引。Chat最新静态review关闭原P1，四个新增P2单列。

## 现存日志重新计数（不是重跑）

历史verification.json原样保留，新增同目录test-summary.json。摘要包含源manifest SHA、源commit、各步骤日志SHA和各Java类的tests/executed/failures/errors/skipped；不提交可能含原始应用输出的整份日志。源码/命令可重跑验证，日志SHA用于原文件在场时校验，不意味着仅有摘要能重建原日志。

| 原任务与目录尾缀 | 命令 | 按本次原日志计数 | 摘要结论 |
|---|---|---|---|
| SPEC-AUDIT-001 / 20261003T230750Z-14ee53bf | mvn -pl hify-app -am test | 495项，406实际运行，89跳过，失败/错误0 | partial；11个PG类未执行 |
| 同目录api-inventory.log | mvn -B -pl hify-app -am -Dtest=ApiContractInventoryTest -Dsurefire.failIfNoSpecifiedTests=false test | 1/1、无跳过；67接口库存 | 窄库存测试，不是行为矩阵 |
| SPEC-CHAT-LIFECYCLE-003 / 20261003T231505Z-3104ff49 | mvn -pl hify-app -am test | 498项，409实际运行，89跳过，失败/错误0 | partial；11个PG类未执行 |
| 同目录runtime-tests.log | verify.sh的runtime scope窄选择 | 34项、零跳过 | 与backend用例有重叠，不相加冒充独立覆盖 |

摘要生成命令（对两个目录分别执行）：

```sh
python3 harness/verification_report.py --verification <目录>/verification.json --output <目录>/test-summary.json
```

存在跳过时退出1是预期，不是重跑失败。历史命令的argv没有保存在schema v1中，上表依据当时verify.sh和专项证据给出复现命令；不伪造原argv。

## 新门禁与回归

schema v2记录commandResult与result分开；任一跳过为partial并退出非0，任一步命令/测试失败或缺逐类结果为failed。backend自动配置已有Docker context供Testcontainers使用；Docker不可用不会用skip取得通过。migration原有独立零skip门禁不移除。

红灯：在隔离临时目录用e43369b的旧verify.sh，合成Maven退出0、2项中1项跳过；`test_shell_gate_rejects_successful_maven_with_skips`预期非0，实际0/passed而失败。无业务服务/真实库参与。修复后该用例和零skip正向用例通过；报告解析覆盖ANSI、重复reactor汇总不重算、空日志、测试失败、命令非0、缺日志、非Maven不编造计数；13项Harness Python测试通过。最初新增测试入口尚未实现时的5失败1错误仅属开发状态，不作为旧行为的红灯证据。

本任务只做文档、计数器和制度门禁；PG及完整F01-F38必须由SPEC-VERIFY-001重新执行，当前不宣称真实Provider、管理全CRUD、上线或全功能通过。

收尾：门禁实现3dccac0，文档/历史摘要1802587；本任务 `harness/evidence/SPEC-AUDIT-002/SPEC-AUDIT-002-20261003T232617Z-bdeff028/verification.json` 的5个Harness步骤全部退出0，Python13项通过。此scope不含Maven，testCoverage=not-assessed，不能称后端已重跑；门禁脚本对合成Maven结果的红/绿是隔离制度测试。
