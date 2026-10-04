# SPEC-INPUT-HYGIENE-002：绑定及集成补强

基线d391d2b（001生产代码add75ed）。本片只改本地Agent绑定准入、集成测试和规格；没有数据库迁移、依赖变更、浏览器测试或132部署。

## 红灯边界

- 测试989aa50：H2/PG各14项，均5失败、0skip；三种目标ID的PG请求实际500；H2分别404/409；tools错误正文回显非法输入。第五项失败是测试把回读nodes的第二项当END，而接口按nodeKey排序；这是测试错误，不算产品缺陷。
- bb76d14按nodeKey精确选END再重跑，H2/PG各14项、各4失败、均0skip；保留原始失败记录。绑定的400及固定错误文案反例作为产品红灯，enabledTools与深层JSON本来就受001保护，只增加集成证据，不能称新修复。
- 首轮修复窄测`narrow-green.log`遗漏TextInput的import导致编译失败，应用集成测试没有执行；不得以该轮common的2项通过当成修复通过。补import后独立重跑，不覆盖该记录。

## 验收范围

四类绑定先以合法数据实际创建，HTTP及直接service坏请求400/40000，完整Agent回读（包含更新时间）与绑定表行不变；合法替换有差异的正向对照。目标ID、工具名和直接service的agentId均检查。MCP使用合成READY目录、两个READ工具，不发现/调用外部Server或真实凭据。

深层JSON覆盖数组中的字符串、数组里的对象键；先合法创建并完整回读config，再做坏创建/更新，最后合法嵌套更新；数组位置不被当作节点身份。create enabledTools有合法calculator绑定对照和零行变化断言。

补import后窄测30项通过（H2 14、真实PG 14、unit 2），失败/错误/skip为0；完整backend/harness结果随后补充。原始日志/XML不提交，只提交脱敏汇总及SHA。读路径仍归003，不能把本片说成所有NUL入口已消除。
