# 132 能力增量受控发布

入口：https://118.196.123.132/hify/ 。2026-10-05 00:40:21 CST开始本轮任务，约定窗口之后执行；00:50:21 CST收尾门禁完成，不把这个时间说成精确切换时间。只操作原版Hify，无其他站点/服务配置变更。机器结果见`harness/evidence/CAPABILITY-ROLLOUT-001/deployment-summary.json`。

## 身份与部署

应用源码与六scope验收的a510191完全一致，backend/frontend/deploy树分别为3b42842e/98f555b6/d3b11e66，无源码diff。重新打包jar及使用`VITE_BASE_PATH=/hify/`的前端，不是门禁时同一个二进制。安装器是DEPLOY004独立复核的58b2a0ea，jar5ee59af0、indexa724db59、snippet6c43b0a0；本地、上传和安装后SHA一致。后续仅修改harness验证，不重装。

预检Python3.12.3、V23成功、三类在途0、旧未标记START.inputs记录0。空间预算1,120,165KiB（含800,000KiB保留量），可用2,307,544KiB，通过后才创建全新发布目录。systemd oneshot安装退出0，V24共24项全部成功，health200。停旧服务后、迁移前生成440,100字节dump，权限0600，pg_restore仅做list检查，没有恢复演练。凭据仅比stat元数据，未读取内容。最终可用2,075,076KiB，95%，未擅自删除旧目录或备份。

## 实际验证

- 具名输入：`0|false|0.0000001`，三个非法输入400，发布schema回读不变；真实PG执行记录回读一致。
- 未授权HTTP节点发布400、版本数0；没有打开白名单，不声称线上成功GET已验证。
- 豆包`doubao-seed-2.1-lite`真实运行START→LLM→END，三节点SUCCEEDED，输出42。补验run `aa901557-9964-409a-a24a-aa0ef1777cb8`，是一次简短通路验证，不是质量/时延benchmark。
- 上次部署保留的合成会话`be99eb3a…`继续运行得到42，本次GET回读的会话版本与新Run版本一致。DEPLOY007没有记录当时的agentVersionId，因此本次证据不能证明它跨发布未变；新会话/幂等、输入NUL拒绝等23项current检查通过。
- 直连及/hify分别上传2MiB、恰好10MiB返回202，10MiB+1和13MiB返回JSON413/41300；检索仅为bootstrap下合成原文命中，不是语义模型。
- 显式选Demo的真实浏览器两组场景、5个持久Run全部COMPLETED；计算器391，问候0工具，三次时间问题各1次current_time。同会话多轮固定版本一致。使用Mock，不能当作豆包浏览器测试。
- 收尾harness78项、typecheck/build通过，7步骤退出0，schema3/aa6ddc2/16:50:21Z。后端没有再次重跑，沿用源码树相同且已独立复核的CAPABILITY-VERIFY-001六scope证据（667/125/34/24，不相加）。收尾本地普通前端构建没有覆盖线上/hify构建产物。

## 两轮失败完整保留

第一轮164021Z-fac60c69安装成功但验证blocked：能力脚本把旧会话GET误拼成/api/v1，正确是/api；旧浏览器测试默认首项为Demo，线上实际首项为之前创建的售后Workflow Agent。current/prefix/direct和最终身份检查均通过。第二轮164515Z-acab6763修正后能力全过，浏览器容器click未打开选项列表。第三轮164805Z-cdfac5e6通过正常focus/ArrowDown选择Demo，只补浏览器与最终身份并收尾，不第三次调用豆包。没有修改产品去适配测试，但独立smoke只部分覆盖原两条浏览器用例，不是逐条等价替代。原spec没有修改，也没有在线上重跑通过。

浏览器覆盖差异：smoke未核对会话POST响应里的agentVersionId、Run与该会话创建版本的相等关系、streamUrl路径、每轮结束“运行”按钮重新出现，以及时间回答不含“已收到”。默认首项为Demo的断言被有意改成显式选择Demo；不能再以此证明默认选择行为。新增POST 202、GET回读COMPLETED及版本一致检查，但回读的outputMessage仅记录，没有内容断言。多轮UI的COMPLETED/工具数/最后一条回答可能读到上一轮显示，不能把这些UI检查当作严格的逐轮收敛证明。

脚本顺序及首轮失败位置表明两轮都执行了豆包步骤；首轮没有保存runId，只有第二轮有完整的具名运行结果，不能声称两次都有独立可回读的调用证据。第三轮通过SHA绑定复用第二轮capability结果。失败摘要、原日志SHA与blocked历史均保留。

复核后另附`harness/evidence/CAPABILITY-ROLLOUT-001/remote-observations/`：从当时本地留存的preflight/install/final日志逐字节复制的无凭据输出，补足摘要中的实际远端SHA、dump元数据、V24和在途数记录。它们只是发布方保存的观察，不是独立远端复验或签名。其余原始日志仍gitignore；Python78项、pg_restore --list未重跑，不把archive说成可复验远端。三个历史run目录未改写。

## 未完成边界

线上没有embedding Provider，SEMANTIC代码可配置但尚非可用线上语义检索；完整父目标CAPABILITY-DEPLOY-001继续追踪，不因本切片完成而降低其标准。成功GET只有本机受控fixture+真实qwen证据。服务TLS仍自签、入口仍无认证；只有测试客户端忽略证书校验。合成草稿按本次ID归档，会话/Run/不可变版本无删除接口，保留；不枚举或删除真实用户会话。smoke失败不自动回滚V24，安装超时先查现有unit，不重跑。备份同盘，磁盘余量约1.98GiB，下一次发布仍须重新估算。
