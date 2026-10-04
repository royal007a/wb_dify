# 发布故障路径与smoke补强（SPEC-DEPLOY-002）

基线cf7783b，红灯测试1219026，2026-10-04。仅本地代码与隔离夹具；没有连接132，没有重复部署，也没有读取真实凭据。此前132成功部署及最终539项门禁不被改写。本任务仅选harness scope，因为未改应用/前端/schema；不能称重新跑了后端539项。

## 反例与验证

- `python3 -m unittest discover -s harness/tests -p test_deploy_smoke.py`：旧脚本5项中4项失败。分别是意外202漏清理、首次清理异常终止后续清理（有/无主错误）、不相关非空结果未拒绝。正向成功用例通过。
- `test_deploy_installer.py`读取基线1219026的安装器文本到隔离副本（不更改仓库生产脚本），运行停止后迟到Run/Workflow及三种信号用例：2项、5个subtest失败，旧脚本信号退出为负信号值而非128+signal；迟到工作未阻止升级。
- 修复后`python3 -m unittest discover -s harness/tests -p 'test_deploy*.py'`：9项通过。Fake HTTP覆盖精确文档ID与短句分别缺失、意外202、逐项清理、主错误保留；安装器成功路径/迟到工作/健康失败/三种信号均覆盖。
- shell测试仅改写副本的绝对路径到TemporaryDirectory；systemctl、PG、nginx、curl、id/stat/df等为假命令。真实tar/cp/install/mv仅操作临时目录。生产脚本没有测试root覆盖开关。不是Linux systemd、真实SSH断线、真实PG关机恢复或磁盘满演练。

原始日志默认不入Git。证据根`harness/evidence/SPEC-DEPLOY-002/SPEC-DEPLOY-002-20261004T005922Z-a988d37f/`：

| 日志 | SHA256 |
|---|---|
| smoke-red.log | f37ab753c2b0b77b6775ce78d6fa7aecc05a82677dfd25d01642dc08a4eb98bb |
| installer-red.log | 2644d64684dd79023565ef27fefaffb8a9285d98bad171f9f0b8538f49d1f311 |
| deploy-green.log | 2b7921de28c244e543faf6f1b4990aae94fa4513610a687344bece3594e18833 |

## 六条复核意见的处理边界

1. 迁移后失败继续停新服务等人工评估，未承诺高可用或自动回滚。健康轮询默认60次、可配1..300；每次curl最多2秒加间隔，非硬60秒。故障夹具验证两次失败后停止、不恢复DB。
2. 捕获HUP/INT/TERM，转非零退出后一次EXIT处置；文档给systemd-run脱离SSH示例，**未在132执行该示例**。等待子命令、不可捕获信号及二次中断仍非保证。
3. previous-dist仅作手动恢复材料；原计划文案已纠正。不增加可能跨schema错误回退的自动恢复。
4. 预检和停止后均检查AgentRun与直接Workflow RUNNING；停止后仍有记录则不换jar/迁移，尝试启动旧实例恢复。不能阻止预检到停机间的新请求被中断，不称维护模式或零停机。
5. 检索必须非空且所有候选含本次文档ID和合成短句，两个条件各有负例。仍只证明合成索引/回读，不证明语义质量。
6. 每个意外202先登记ID再断言；finally尝试全部自身ID及KB，失败汇总，原始异常保留为cause及诊断。创建成功但网络丢失ID仍无法自动清理；不枚举删除未知数据。清理中再次被信号杀死亦不保证完成。

TLS仅测试连接显式关闭校验、不固定证书；同盘备份非异地恢复，96%磁盘问题不通过删除别人的数据解决。源部署保持不变，脚本补强需未来发布时才使用。

最终门禁：代码426de39，schema 3，2026-10-04T01:06:42Z，harness scope五步exit0、result=passed，Python共45项通过（包括9项部署夹具）。日志中的假Maven failed是Harness自身负例测试的预期输出，不是本次真的运行Maven。未重跑后端/浏览器/132，原发布的539项证据保持原时间和提交。
