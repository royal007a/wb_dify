# CAPABILITY-ROLLOUT-001

用户十小时目标明确授权原版Hify实现、部署、验证。线上无embedding Provider且空间不足以安全新装模型；不静默降低CAPABILITY-DEPLOY-001的全能力验收，拆出已能独立交付的代码上线。请求用户补充长期embedding endpoint；本task不建立长期Mac反向隧道、不开放新网络端口、不修改Provider/MCP策略。

1. 验证a510191六scope passed及源码树等价；包装jar、/hify前端及既有snippet，复制独立review过的安装器2f7e340。
2. 只读预检Python>=3.11、在途三表=0、V23成功、旧inputs不兼容版本=0、磁盘空间。容量预算包括压缩包、incoming、旧jar/dist、数据库大小估计及至少800000KiB保留；不足停止，不删历史。
3. 上传到全新spec-verify目录，校验本地/远端SHA；systemd-run执行安装器，等待成功并确认V24、service/health、备份非空/0600/list、key元数据未变。不自动恢复数据库。
4. 只对自建合成资源执行新LLM/typed输入、新旧会话与NUL/上传smoke；模型使用用户已配豆包，不修改其配置，不读取Key。工作流/Agent草稿、KB/文档按本次ID归档，运行/会话等无删除接口的合成记录保留。
5. 在线embedding、生产GET endpoint授权保持显式未完成，不打开默认白名单。真实本机GET→LLM与语义证据在CAPABILITY-VERIFY-001，不能冒充132结果。归档摘要，收尾harness/frontend；与CC协调切换窗口，只reload本项目既有snippet（不改其他server）。

## 切换后的失败与证据口径

- 上线jar/dist是a510191同一源码树重新构建的产物，前端使用/hify构建参数；不称测试时同一个二进制。记录本次本地/远端SHA。
- installer成功后新版本已经提供服务，后续smoke失败不自动停服/回滚。编排记录所有独立smoke结果后非0退出；由当前发布负责人核查health、在途任务、失败是否只是外部Provider时延及数据/迁移风险，决定停止新应用或继续诊断。旧jar兼容V24未演练，不能盲回滚，更不能覆盖生产数据库。
- 豆包只有一次smoke，不为通过而隐藏重试；45秒节点上限、65秒HTTP等待，超时/空结果按失败保留。独立基础smoke和最终状态仍会继续收集。
- 360秒等待超时打印unit状态，systemd任务可能仍运行；SSH超时也不代表unit已停。不得重跑，先只读查询该unit。release与unit存在检查防止误重复执行。
- chat/chat-time浏览器只在自己的新页面创建合成会话，旧会话检查只使用DEPLOY007保留的已知合成ID。不枚举真实会话；会话/Run/不可变版本无删除接口，明确保留，草稿按own ID归档。

## 首轮红灯后补验（不重新安装）

20261004T164021Z-fac60c69 已完成安装，V24成功、健康、SHA一致，current/prefix/direct通过。能力脚本错误地把旧会话GET拼成/api/v1而非/api；两条旧浏览器测试默认选中Demo，但线上首项是演示售后Agent。保留首轮blocked和失败日志摘要，不把脚本错误算产品成功。

修正probe的legacy GET路由，独立浏览器明确选择Demo；仅重跑能力smoke/浏览器和只读最终身份核对，之后harness/frontend收尾。不重新打包、上传、停服或迁移。能力smoke包含第二次明确记录的豆包调用（首轮已过该断言），不是超时自动重试。旧回读404的正确路由已只读核实会话和版本仍在。
