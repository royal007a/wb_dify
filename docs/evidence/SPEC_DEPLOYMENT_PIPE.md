# SPEC-DEPLOY-005：SIGPIPE边界

基线5330c15；仅临时目录、假systemctl/PG/nginx，无132/真实凭据。

测试用os.pipe创建真实无读端管道，而不是关闭fd。分别让stderr断管道进入late/health失败，要求原rc=1、正确服务状态；stdout断管道到最终is-active时要求健康服务不被停止、新snippet/index不回退。假systemctl显式恢复默认SIGPIPE，以匹配真实命令而非Python默认忽略；quiet不输出但仍返回状态码。额外强制quiet返回3，要求照常失败并停止新服务，不把quiet当成功。

旧实现新增8个subtest失败；修复仅on_exit在保存result后trap空PIPE，以及最终active检查加--quiet。不是全流程忽略SIGPIPE，也不承诺断管道后的诊断一定能输出。绿色与门禁结果待本轮补录。
