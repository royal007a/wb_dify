# SPEC-DEPLOY-004：产物迁移目标与 smoke 自有数据清理

范围：本地安装器与隔离假命令/HTTP测试；本片不执行132发布，不构成数据库恢复演练。产品发布门禁和远端部署另行记录。

## 修改与边界

- 从实际待发布jar提取SQL/Java迁移序列，停服前检查产物布局和数据库前缀，启动后核对完整目标；未来V24不再被常量23拒绝。Flyway仍负责真正的执行和checksum校验。
- 三张工作表独立检查，索引含PENDING（网络embedding阶段）和RUNNING；假psql按FROM区分，迟到Agent、Workflow、两种索引状态和查询报错不再共用一个伪分支。
- smoke先登记本次创建响应中能识别的ID，再断言状态/形状；KB也在finally范围。逐项清理、保留主异常类型及KeyboardInterrupt，附注清理错误。不枚举既有数据，不猜测未知ID。
- 仍不是无停机部署；迁移后失败停止新服务，数据库和静态文件不会自动回滚。主流程断管道的既有边界仍在，发布使用systemd-run/journal。详见OPERATIONS。

## 先红后绿

基线acc475e。日志为本机临时记录，以下SHA可用于本机核对，但未提交原日志，不能声称只凭仓库可重算日志。

|运行|观测|日志 SHA-256|
|---|---|---|
|新增安装器反例对旧代码|2个方法、19个失败子例；70.158秒|f4d3bb0fbb2c54e920cfdea3f85d87928268f9ed39f68b69c2f52e5cc139d975|
|新增smoke反例对旧代码|8个方法、5失败4错误|05b2ad50d4ae60dda926609d99cd66831b8c539662fa6384eebee068e075c75b|
|新代码两个测试模块|18/18通过；165.753秒|3547517f3067507bf44788454189fb5d183f274e9d8695d67c46c731e68cb1b4|

执行：`python3 -m unittest harness.tests.test_deploy_installer harness.tests.test_deploy_smoke`。安装器的迁移矩阵在sh和可用dash下验证：损坏jar、空/重复/缺号/坏命名迁移、降级、既有失败迁移均在stop前拒绝；目标V24而实际V23会停止新服务，实际V24成功。原有信号、关闭stderr、SIGPIPE、quiet状态、健康次数及备份断言保留。

所有systemctl/psql/nginx等由PATH假命令代替；cp/tar/mv只作用临时目录。测试jar是含SQL和Java迁移条目的合成ZIP，不是实际Spring/Flyway启动，因此实际V24迁移仍须完整产品门禁与发布验证。smoke全部为假HTTP，没有调用外部服务。

完整harness门禁待代码提交后由原子runner补齐；当前不宣称本任务正式完成。
