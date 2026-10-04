# 结构化输入切片证据

计划d8118d1。目前未部署；完整门禁与独立review待补。契约见spec/SPEC_WORKFLOW_INPUTS.md。

最终补录：0fc126f 的完整harness/backend/frontend门禁于2026-10-04 15:48:21Z passed，invocation `147fc20d-2801-4bec-8aad-b0043f9c396a`；backend96类667项全部执行、失败/错误/skip/flaky全0，Harness74项91.354秒，typecheck/build通过。证据目录 `harness/evidence/WORKFLOW-INPUTS-001/WORKFLOW-INPUTS-001-20261004T154206Z-aa4ea297`；verification SHA `ff1aeff947a46ac471b8db7a5a4a7fba779060e2a5c5bc6bcf228efe8c8a3e70`，backend摘要SHA `106e2c44babdd12469e1f2fdc7a8e9d91f5bfd39a50601e1ab7d8da4ebfa8adc`。源码树及空diff见source-identity。没有迁移/部署/真实模型范围。

独立复验确认原四项P2处理成立、无P0/P1；只读及Jackson/Node片段不冒充重跑本项目。未编辑可选数字默认值由浏览器浮点覆盖的问题另归WORKFLOW-INPUTS-002。单测的mapper未变断言只针对单测对象，不证明Spring全局配置；局部reader由源码审查支持。JSON数值可序列化成科学计数法，不改变其数值；规范十进制仅承诺模板输出。Agent发布409和16字段是补回归覆盖，不是修复前必红的突变试验。

- 单测第一轮56项（输入3+图28+引擎14+控制11）通过，零失败/错误/skip；追加外部execute次数对照后23:23:27 CST重跑57项通过，0失败/错误/skip。
- HTTP窄测首轮失败：H2首个用例误认已按key排序列表第一项为START，修为按type定位；PG未执行成功，手动命令没有Harness的host/nonProxyHosts环境，localhost解析失败。首轮日志SHA `f42a0f46f1feb68c02d6100e25dd648ed2eed2d110ae9294eae917cf445bceac`。
- 第二轮H2五项通过，但PG仍因SOCKS路径的127.0.0.1解析失败；没有算作通过。第三轮补齐Harness同样的HTTP/HTTPS/SOCKS nonProxyHosts，独立hify-verify-20261004 context，23:21:54 CST，H2与真实PG各5项全部执行、0失败/错误/skip。PG每例select version确认；没有修改共享default/Dify。
- 受控浏览器：独立127.0.0.1:15173 Vite，workflow-inputs两项+既有management一项，23点后首轮3/3通过；发布schema而非草稿、缺文本不POST、false/0真实JSON、两处表单复用、旧输入和失败后重试。测试路由打桩，不代表真实后端或模型。
- Typecheck通过。原始窄测日志保存在/tmp/hify-inputs-*，不含真实密钥；正式门禁另行保存计数/SHA。不将CC通过或旧后端证据当成本次验收。

## 首轮完整门禁（保留红灯）

87a815f 的 `WORKFLOW-INPUTS-001-20261004T152444Z-3b5139a3` 在 23:30:34 CST 完成，整体 failed / 任务 blocked。backend、frontend-typecheck/build 返回0；harness-python-tests失败，不能将这些分项成功算成整体验收。

失败为安装器测试的三组 sh/dash 信号子场景未在5秒内到达成功后的门闩，尚未发送测试信号。同一测试单独重跑仍有两组相同失败；再用30秒观察窗、不改安装器运行一次，5.163秒到达门闩、19条假命令已执行，放行后exit0。测试夹具进程启动耗时超过原等待预算，另开 HARNESS-DEPLOY-TIMING-001 修正；不删断言、不重写此失败证据。

独立静态review未发现P0/P1；数字精度/表示、Unicode空白、清空可选数字与旧同名inputs配置兼容风险仍需处理和补测，不能因backend本轮绿色宣称关闭。

## 复核补强过程

- 安装器夹具单独任务HARNESS-DEPLOY-TIMING-001在e0a4c5d完整harness 74项通过，不修改生产脚本，不追改首轮红灯。
- 数字局部精确解析/模板规范十进制、必填文本ECMAScript空白、数字清空省略：单测59项通过、typecheck通过。随后HTTP窄测首次14项里2项失败，发现自定义反序列化器把“省略inputs”也返回NullNode，破坏旧userMessage接口；已补getAbsentValue区分省略和显式null，并补单测。该失败是本次改动引入的真实兼容回归，记录不删除；重跑结果另记。
- 受控浏览器4/4通过（包含既有management），新用例证明Element Plus清空数字时省略字段，以及已发出的旧版本GET/运行POST晚到时不覆盖新对话框。仍是路由打桩，不代表生产网络。
- 132仅执行一条SELECT，旧发布START含inputs且无发布标记的记录数为0；不改库、不调模型、不碰其它服务。部署前会重复检查。
- 修正省略/显式null后，23:41:07 CST重跑：WorkflowInputsTest 6/6、H2 7/7、真实PG 7/7，全部执行、零失败/错误/skip。原始JSON小数、发布后默认值经存储回读、1e-400不下溢、真正越界小数拒绝、原有无inputs接口继续202均有断言。完整门禁与独立复验仍待补。
