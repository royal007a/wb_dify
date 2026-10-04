# 结构化输入切片证据

计划d8118d1。目前未部署；完整门禁与独立review待补。契约见spec/SPEC_WORKFLOW_INPUTS.md。

- 单测第一轮56项（输入3+图28+引擎14+控制11）通过，零失败/错误/skip；追加外部execute次数对照后23:23:27 CST重跑57项通过，0失败/错误/skip。
- HTTP窄测首轮失败：H2首个用例误认已按key排序列表第一项为START，修为按type定位；PG未执行成功，手动命令没有Harness的host/nonProxyHosts环境，localhost解析失败。首轮日志SHA `f42a0f46f1feb68c02d6100e25dd648ed2eed2d110ae9294eae917cf445bceac`。
- 第二轮H2五项通过，但PG仍因SOCKS路径的127.0.0.1解析失败；没有算作通过。第三轮补齐Harness同样的HTTP/HTTPS/SOCKS nonProxyHosts，独立hify-verify-20261004 context，23:21:54 CST，H2与真实PG各5项全部执行、0失败/错误/skip。PG每例select version确认；没有修改共享default/Dify。
- 受控浏览器：独立127.0.0.1:15173 Vite，workflow-inputs两项+既有management一项，23点后首轮3/3通过；发布schema而非草稿、缺文本不POST、false/0真实JSON、两处表单复用、旧输入和失败后重试。测试路由打桩，不代表真实后端或模型。
- Typecheck通过。原始窄测日志保存在/tmp/hify-inputs-*，不含真实密钥；正式门禁另行保存计数/SHA。不将CC通过或旧后端证据当成本次验收。
