# 132增量发布：Run输入与Chat恢复

授权goal:01a07e90-58c6-7f00-8ce1-fc659dd6c39c。必须等待SPEC-DEPLOY-005独立review无阻塞；hify-cc、其他服务、凭据和TLS证书不变。

1. 确认backend/frontend树等于f0dd199（547项后端、输入001的32项受控浏览器，前端未再变）。打包用skipTests，不能当测试证据；前端/hify前缀另构建。
2. 只读检查132 Hify active、空间≥800000KiB、AgentRun/Workflow/知识索引无执行中任务、Flyway=23。只读主密钥元数据，不读内容。当前产物无新迁移，未来V24仍需004。
3. 新release目录上传独立SHA归档与已复核安装器。stop旧实例、非空dump及pg_restore --list、换jar/检查健康/schema、nginx-t、发布assets/index。使用持久systemd oneshot脱离SSH；不能自动恢复数据库。
4. 所有远端测试用自建合成对象，上传脚本限制目标且只清理自身ID。查询真实Run完成（Mock模型）、20001及NUL返回400、正文不回显、有效请求正向。仅接受本次TLS自签测试，不改客户端安全策略。
5. 比对jar/index/snippet SHA、服务及运行中计数、磁盘、备份size；frontend/harness最终门禁、源码等价及远端摘要分开提交复核。失败保留证据，不盲启旧jar、不删用户/其他服务数据。

保留边界：无登录ACL、非真实供应商准确性；上传/管理NUL另开项；同盘备份未恢复演练；004的非标准响应ID及索引并发准入仍未全面实现，成功smoke不能关闭这些故障路径。
