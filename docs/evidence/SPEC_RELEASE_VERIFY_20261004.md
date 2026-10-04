# 最新版本重新部署前完整验收

用户授权重新完整部署（om_x100b6314d40470a4b2e14a8024c780f）。代码HEAD 42db727，backend树85586d8c、frontend树1e257aae，源码diff为空。旧的满盘失败记录不改写。

2026-10-04 08:26:10Z完成SPEC-RELEASE-VERIFY-001：六scope、12步骤全部exit0；backend87类602项、真实PG/migration14类118项、runtime34项、eval24项均全执行且failure/error/skip/flaky为0；harness Python74项通过，前端typecheck/build通过。各scope有重叠，不能相加为独立功能数。另独立Vite/Chromium受控HTTP和EventSource浏览器38项通过；不是线上或真实模型验证。

证据根`harness/evidence/SPEC-RELEASE-VERIFY-001/SPEC-RELEASE-VERIFY-001-20261004T081503Z-5ee00fdd/`。verification schema3、严格证据passed；摘要及每步日志SHA已本地重算。method-evidence脱敏输入重算JSON/MD逐字节一致：57具名子场景pass、0fail/not-run，68路由/38功能组只为映射，不代表全路由整体通过。

默认Colima满盘没有删除或重启；本次新建独立hify-verify-20261004（2CPU/4GiB/12GiB数据盘），显式DOCKER_HOST绑定新socket，默认context仍colima。测试后新盘约11GiB可用。宿主机初始44GiB可用，132预检剩余约1.53GiB、服务active、三类在途数0、V23全success；这只是发布前只读结果，不是新版本已部署。

首次提前调用behavior_report时verification.json尚未生成，命令exit1，未生成成功报告；完整门禁结束后重跑成功，不属于产品失败。原始日志/XML不进Git，提交摘要不能还原日志；新的完整结果覆盖当前代码，但不把旧Chat/知识任务的blocked运行伪造为通过，也不宣称开放P2已修复。
