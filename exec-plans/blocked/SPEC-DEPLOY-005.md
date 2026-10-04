# SPEC-DEPLOY-005：安装器SIGPIPE

依赖003完成，本计划等待当前Run输入002原子任务结束后开始。仅临时副本假命令，不访问132。

1. 新增sh/dash无读端管道实验，区别EBADF关闭fd与EPIPE。late/health两种清理保留rc=1；最终is-active的stdout断管道不回退新服务。
2. 假systemctl按真实语义：--quiet不输出，非quiet向断管道写入则SIGPIPE。旧代码失败后仅改最终active检查为quiet、on_exit保存rc后忽略PIPE。
3. 检查实际active=false仍会触发故障收尾，不因quiet绕过状态码；保持原SIGINT/HUP/TERM及stderr关闭测试。
4. deploy窄测+harness门禁，独立复核通过再发布。主流程其他命令断管道可导致失败；不承诺任何输出链路故障下都继续发布。清理期间第二个非PIPE信号仍在明确限制内。
