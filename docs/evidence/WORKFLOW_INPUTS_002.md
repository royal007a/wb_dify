# 未编辑数字默认值

仅修改运行表单和受控浏览器测试，不改后端/部署。可选数字未编辑省略、编辑后按真实Number发送（0不丢）、清空省略，重开版本重置标记。监听用户input/change，不因控件初始化update:modelValue误标编辑。

同一浏览器用例先对修复前表单运行：请求实际为 `exact:0.12345678901234568,tiny:1e-7` 而预期省略，红灯成立；1e-7显示检查在这轮已通过，没有取整。原始日志 `/tmp/hify-inputs-defaults-red.log` 保留。随后修复，另跑全部输入表单和既有management回归；最终计数见门禁与补录。

红灯日志SHA `ad1ce84884d339236e2fb9907865c47e8df17d5c67c9a9a82963313edcf10819`。修复后受控浏览器5/5通过、15.0秒，typecheck通过；完整harness/frontend待补，不把这5项称为真实服务验收。

最终补录：11965bb 的 `WORKFLOW-INPUTS-002-20261004T155241Z-34d2d41d` 完整harness/frontend passed；Harness74项、typecheck/build均通过。后端未改，本轮没有重跑Maven，继续引用001的667项证据而不冒充本轮执行。独立静态复验认可编辑标记逻辑；其提出“必填数字有精确默认值”的前提不符合schema（required=true禁止default），已发送源码与已有负例校准。手动改回原值仍算编辑是明确行为。

仍不承诺用户手输任意精度小数；高精度原始JSON API在001后端已验证，002不重跑或冒充后端验收。此处还把已完成001的计划从blocked移到completed，补正runner从blocked重跑完成时未迁移路径的元数据；未改变历史失败记录或通过结果。
