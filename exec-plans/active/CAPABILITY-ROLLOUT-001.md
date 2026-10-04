# CAPABILITY-ROLLOUT-001

用户十小时目标明确授权原版Hify实现、部署、验证。线上无embedding Provider且空间不足以安全新装模型；不静默降低CAPABILITY-DEPLOY-001的全能力验收，拆出已能独立交付的代码上线。请求用户补充长期embedding endpoint；本task不建立长期Mac反向隧道、不开放新网络端口、不修改Provider/MCP策略。

1. 验证a510191六scope passed及源码树等价；包装jar、/hify前端及既有snippet，复制独立review过的安装器2f7e340。
2. 只读预检Python>=3.11、在途三表=0、V23成功、旧inputs不兼容版本=0、磁盘空间。容量预算包括压缩包、incoming、旧jar/dist、数据库大小估计及至少800000KiB保留；不足停止，不删历史。
3. 上传到全新spec-verify目录，校验本地/远端SHA；systemd-run执行安装器，等待成功并确认V24、service/health、备份非空/0600/list、key元数据未变。不自动恢复数据库。
4. 只对自建合成资源执行新LLM/typed输入、新旧会话与NUL/上传smoke；模型使用用户已配豆包，不修改其配置，不读取Key。工作流/Agent草稿、KB/文档按本次ID归档，运行/会话等无删除接口的合成记录保留。
5. 在线embedding、生产GET endpoint授权保持显式未完成，不打开默认白名单。真实本机GET→LLM与语义证据在CAPABILITY-VERIFY-001，不能冒充132结果。归档摘要，收尾harness/frontend；与CC协调切换窗口，只reload本项目既有snippet（不改其他server）。
