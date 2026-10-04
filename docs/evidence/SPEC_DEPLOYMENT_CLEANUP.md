# 安装器stderr与成功边界（SPEC-DEPLOY-003）

基线4eff833，计划9db4e34，红灯测试3c88be6。只运行本地临时目录中的安装器副本；systemctl/PG/nginx/curl等均为假命令，没有访问132或真实凭据。

旧脚本运行`python3 -m unittest discover -s harness/tests -p test_deploy_installer.py`：7项，17个subtest失败。新三项覆盖sh与dash：关闭stderr的迟到工作/健康失败；非法次数0/000/301和缺jar/index/snippet/key；成功后诊断SHA处分别HUP/INT/TERM。原四项正向/迁移后失败/信号路径保留。

修复只改变脚本控制流：result=$?仍为on_exit第一句，随后set +e；服务动作先于可失败诊断，printf显式忽略失败，最后退出原result。预检不再依赖AND列表中间命令的errexit。完成最后实际健康检查后立即解除trap，诊断失败不再停止新服务或回退snippet。

首次绿色候选12项有1失败：测试错误要求解除trap后仅给父shell发送SIGINT必为非0；macOS sh在子命令正常结束后可返回0。调整为接受0或信号退出，仍要求服务active、新snippet/index、仅一次初始stop、无回退日志；这不是放宽故障阶段的原退出码断言。故障阶段stderr关闭仍必须恰好退出1，三种信号仍要求128+signal。

重新执行`python3 -m unittest discover -s harness/tests -p 'test_deploy*.py'`：12项通过，26.480秒。最终harness scope在代码d6d67c6上执行，schema3、passed，Python共48项通过，零失败；没有运行Maven/浏览器/远端。假Maven负例的failed输出是Harness自身测试，不是后端失败。

证据根：`harness/evidence/SPEC-DEPLOY-003/SPEC-DEPLOY-003-20261004T012841Z-fefe05f5/`。原始日志不入Git，SHA不等于可还原日志：

| 日志 | SHA256 |
|---|---|
| installer-red.log | bbefb223e8f8c86dce3ad96d8586b434e23f9f87ef26d20d4c1e5890056dd777 |
| deploy-green.log（首次候选失败） | cffead99e31b38fff6b8408597a499af3f43bed43a049b80950206fe0988edcb |
| deploy-green-rerun.log | e276a9cb2a76767c8d89cd15e9377443ae4a25dbb4f0b46004ffa38cbab51862 |

不宣称真实SSH断线、systemd、磁盘满、DB恢复或生产发布已验证；SIGKILL、第二次信号和systemctl失败不在保证范围。SPEC-DEPLOY-004仍跟踪smoke非标准响应、schema目标和索引任务。独立复核通过前不重新部署。
