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

## 完整门禁与复核

2026-10-04T02:56:20Z，代码2f37ae0，schema3，harness/backend passed；84类577项全部执行，failure/error/skip/flaky均0，H2/PG输入卫生各14项。Harness Python54项通过。`backend-tests.tests.json` SHA256=5f25eb9b5d33d39601d419e00d3aea9567ff66be2f66ea6b7064df217aff2efe。源码backend=ea4e78ff、frontend=8f83bc47，受测源码diff为空。

证据在`harness/evidence/SPEC-INPUT-HYGIENE-002/SPEC-INPUT-HYGIENE-002-20261004T024435Z-01f55785/`；报告50个具名子场景pass，68路由/38功能组不是整体通过；脱敏输入离线重算JSON/MD逐字节相同，reproduction.json记录SHA/命令。原始XML和日志不提交。

独立复核om_x100b632fb09f44a4dfa26b2dd8d8033为静态阅读，未重跑测试：P2-1/P2-3修复，无P0/P1/P2，正式摘要复算另行记录。保留P3：每种绑定测试只有单个元素，多元素后项非法的全列表预检只由代码阅读证明；archive/publish/clearWorkflow仅路径ID及内部null列表的普遍参数契约不在本片。不能把“真实容器一般拒绝%00”的推断当成本轮HTTP网络证据；没有测试Tomcat URI拒绝行为。未部署132。

后续正式收口：mymacclaude回复交接消息om_x100b6328406100a0c021f9ee209fc6c，报告在2a9ca62的archive隔离副本实际复算tests/method-evidence SHA、源码树，以及portable离线JSON/MD，均与上述记录一致（50子场景pass、0fail/not-run）。这是独立证据复算，不是重跑Maven；两条P3仍保留，读路径由003另行验证。
