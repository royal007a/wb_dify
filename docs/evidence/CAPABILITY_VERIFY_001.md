# 能力增量完整验收（未发布）

被测a510191，完成2026-10-04 16:20:46Z（北京时间次日00:20:46）。证据目录`harness/evidence/CAPABILITY-VERIFY-001/CAPABILITY-VERIFY-001-20261004T160904Z-4784739a/`。

## 本轮实际运行

|范围|结果|
|---|---|
|harness|78项通过，183.306秒，状态/progress/API库存/shell共五步退出0|
|backend|96类667项全部执行|
|migration PostgreSQL|15类125项全部执行|
|runtime|7类34项全部执行|
|eval|7类24项全部执行|
|frontend|typecheck和build退出0|
|另行浏览器|路由打桩42/42，非线上真实CRUD|
|真实bge-m3:latest|本机1024维，三条合成中文改写top1全部命中，69/36/58ms|
|真实qwen2.5:0.5b|受控GET只调用一次，START→API_CALL→LLM→END成功，输出42，1604ms|

四个Maven摘要均failures/errors/skipped/flaky=0；不同scope重叠，不能相加为独立测试量。Inputs H2/PG各7项、Semantic H2/PG各7项均执行。Docker仅用`colima-hify-verify-20261004`，不切全局context。真实模型检查为自建loopback H2进程，已退出，合成数据，不是效果benchmark、大库性能或远端服务验收。打包时skipTests只用于生成live产物，不能代替后续完整测试。

## 身份与重算

invocationId `64d81fc9-872e-49fe-bc4b-9bdcae583d0b`；backend树`3b42842e`，frontend树`98f555b6`，deploy树`d3b11e66`。live前后及完整门禁后都检查源码无diff、无未跟踪源码；source-identity记录空diff SHA。测试期间只有runner状态/证据写入。

四份摘要SHA已逐一复算匹配verification：backend `4bf2fa41`，migration `0d65c807`，runtime `92241bea`，eval `8826dc77`。原始XML生成的报告为57个具名子场景pass、0fail、0not-run；导出脱敏method-evidence后离线再生成JSON/MD，逐字节相同。命令与完整SHA见`reproduction.json`。68路由/38功能组是映射，不是全部场景通过。原始日志/XML未提交，不能宣称archive可重跑日志校验或替代真实Maven。

## 发布前边界

安装器DEPLOY-004已有独立隔离18项及本方完整harness78项证据。review无P0/P1，剩Python<3.11时add_note保护、tar列目录管道状态与数据库恢复演练/手册边界；132已检查Python3.12.3，正式发布另做版本守卫。备份只做list检查，不宣称恢复成功。

132仍是42db727/V23，本证据不代表已发布。只读检查在途数0，旧START.inputs无发布标记记录0，剩余2308804KiB；旧jar75188KiB、dist9536KiB、数据库14400535字节。值只代表当次检查，切换前重查。当前线上只有豆包聊天Provider和Mock，没有配置embedding Provider，已向用户询问长期可用端点；不能将本机模型或临时隧道说成稳定线上语义服务。
