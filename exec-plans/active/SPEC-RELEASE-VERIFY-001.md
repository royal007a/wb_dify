# 最新完整验收

授权用户消息om_x100b6314d40470a4b2e14a8024c780f要求重新完整部署。基线95aa65b；未完成DEPLOY004仅两个测试文件已保存到具名git stash，不用于本次发布。

1. 默认Colima仍满盘且运行16个其他项目容器，不删除、不重启、不切换其context。建立独立hify-verify-20261004 profile（2CPU/4GiB/12GiB数据盘，宿主机初始44GiB可用），只向该socket运行本任务Testcontainers。记录实际可用空间与scope。
2. 干净源码提交后run-task执行harness/migration/backend/runtime/eval/frontend完整门禁；任何PG skip或错误不得进入部署。检查XML/summary方法计数与源树。
3. 当前受控浏览器用独立Vite端口重跑，部署后真实132浏览器另算。源码不变前提下将已获证据与仍blocked的Chat/知识任务关联，不删除旧失败。
4. 原始日志/XML不进Git，提交可复算摘要和源码身份。完成只代表当前版本的所选完整测试范围，不声称所有外部模型、权限或残余问题已解决。
