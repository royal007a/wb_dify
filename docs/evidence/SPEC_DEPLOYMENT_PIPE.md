# SPEC-DEPLOY-005：SIGPIPE边界

基线5330c15；仅临时目录、假systemctl/PG/nginx，无132/真实凭据。

测试用os.pipe创建真实无读端管道，而不是关闭fd。分别让stderr断管道进入late/health失败，要求原rc=1、正确服务状态；stdout断管道到最终is-active时要求健康服务不被停止、新snippet/index不回退。假systemctl显式恢复默认SIGPIPE，以匹配真实命令而非Python默认忽略；quiet不输出但仍返回状态码。额外强制quiet返回3，要求照常失败并停止新服务，不把quiet当成功。

旧实现新增8个subtest失败；修复仅on_exit在保存result后trap空PIPE，以及最终active检查加--quiet。不是全流程忽略SIGPIPE，也不承诺断管道后的诊断一定能输出。绿色与门禁结果待本轮补录。

绿色窄测14项通过，38.423秒。首轮最终门禁因同时登记的下一项SPEC-DEPLOY-006误写operation=deploy（合法枚举为deployment）在harness-state失败；这不是产品或脚本测试失败，记录保留，不将该轮当passed。修正元数据后重新执行独立门禁。

重跑最终门禁：`SPEC-DEPLOY-005-20261004T015109Z-f50d1ff1`，HEAD d7b2300（脚本代码609829e未变），2026-10-04T01:51:59Z、schema3、harness passed，50项Python全过。前一轮失败证据`SPEC-DEPLOY-005-20261004T014624Z-fe130b4d`保留；启动重试时首次因未提交plan自动移到blocked目录被clean-tree检查挡下，提交该移动后才启动，没有执行部署。

原始窄测日志不入库：installer-red SHA `7a4bec7d8f38fc0ac9ab44d991074703d20d204fe7757a81012a915ecc524e11`；deploy-green SHA `1aaaadecc5f34d2ff1c4f1c469b5d23188da4a2dd747060a174eec74c0811340`。SHA不能替代日志或真实systemd/网络测试。

独立复核5330c15..609829e：隔离archive实跑14项/39个subtest通过，静态核对无P0/P1、两条P2可关闭，未连接132。PIPE忽略只在cleanup及其exec子进程，systemd启动的Java服务不会继承该设置。残余：主流程nginx等写stderr时仍可SIGPIPE，导致fail-closed停服；生产使用systemd-run/journal脱离SSH管道，本次不宣称脚本对任意断管道仍可完成升级。
