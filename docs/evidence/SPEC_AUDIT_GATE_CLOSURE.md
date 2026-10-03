# 验证门禁复核修复

任务 SPEC-AUDIT-003，基线38afd9a，代码27b30d5。仅原版Hify；不碰hify-cc、132、共享业务库或真实凭据。

## 六项对应

1. Flakes/未知类汇总格式：历史日志解析不再静默丢行，flake不算干净通过。新执行完全不从stdout计数，读取本轮XML的testcase/skipped/error/failure/flaky/rerun字段。
2. 预期类和旧报告：expected-maven-suites.json固定每scope类集合和最少记录数；backend与源码列表双向匹配，同名类跨模块拒绝。maven_step为每次调用创建新reports目录，不清理或复用target。父POM属性激活profile把Surefire输出明确接到新目录。每步摘要记录期望值、逐类计数、XML SHA、执行命令；XML含JVM属性与应用输出，不进Git。stdout伪造100项不改变XML计数；缺类、fork失败、异常XML或无报告一律失败。
3. 89是skip报告记录数，不是精确未执行方法总数。禁用容器可能用一条代表多个方法；原日志数值保留但口径更正，至少89项未执行。新报告同时保存期望下限；部分执行仍partial，不伪造未运行方法明细。
4. finish必须读当前run的schema v3报告，核对HEAD、runId、scopes和必要步骤、passed及零失败/skip/flake；不能只传exit0完成。新制完成记录保存报告SHA，validate重查。runner保留verification partial原因。旧001/Chat003的completed兼容记录在任务description明确注记partial，不回写历史manifest；全面验收仍需SPEC-VERIFY-001。
5. 审计002没有测试Docker context分支，已改为仅源码事实。本任务新增backend-alone合成context测试，断言DOCKER_HOST、Colima overrides、nonProxyHosts传入假Maven；它不是Docker联通性测试。真实Maven各scope证据另列。
6. 三条启动恢复/写库/容量问题统一归SPEC-RECOVERY-ADMISSION-001且各有验收项；子任务监听顺序仍属CHILD-RECOVERY；workflow.started早于校验统一归GRAPH-003。只纠正归属，不宣称修好业务问题。

## 红绿证据

证据根：`harness/evidence/SPEC-AUDIT-003/SPEC-AUDIT-003-20261003T233839Z-7c2f0035/`。

- `red-correct-entry.log`：旧实现三条反例均断言失败（Flakes漏skip、未识别类汇总、无验证直接finish）。第一次调用的Python模块导入错误只算入口误用，不充当红灯。
- `python-green-3.log`：30项Python测试通过，含真实verify.sh加假Maven、fake Docker context、runner partial状态转换，以及XML边界单测。使用临时目录，不修改共享报告。
- 补混合Surefire旧格式不能藏类后，`python-green-final.log`为31项通过；红/绿命令与日志SHA保存在regression-summary.json。历史解析保守拒绝未知格式，不把容器skip数伪装成方法分母。
- `real-inventory/api-inventory.tests.json`：第一次真实Maven返回0，但参数未接入Surefire导致新目录无XML，门禁返回failed。这个负面结果保留；没有退回旧target读取来通过。
- `real-inventory-profile/api-inventory.tests.json`：补显式POM profile后真实Spring/H2接口库存1项通过，0失败/错误/skip/flake。这是68条路由库存一致，不是68条接口全部行为验收。
- `real-scopes/verification.json`：schema v3，命令退出0/result=passed。backend 78类/520项、migration 14类/114项、runtime 7类/34项、eval 7类/24项，各自失败/错误/skip/flake为0，重复用例不跨scope相加。执行`./harness/verify.sh --scope backend,migration,runtime,eval --evidence-dir <根>/real-scopes`；Maven代码基线27b30d5，收尾报告器另带历史日志格式严格化工作树修改（不改变XML路径）。backend单独排在migration之前，因此是真实backend-alone Docker配置/PG联通证据，而非借migration先配置环境。

## 边界

本门禁防漏测与误操作，不防有仓库写权限的人伪造XML或修改manifest；SHA也不是签名。期望最少数从新鲜已观察测试建立，增删测试须评审清单，不代表覆盖率或所有行为的分母。当前没有隐藏已发现的HTTP红灯：fixture与4条失败保存在SPEC-VERIFY-001，后续仍需恢复修复。没有部署。
