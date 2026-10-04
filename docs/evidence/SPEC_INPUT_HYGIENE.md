# SPEC-INPUT-HYGIENE-001证据

基线19a1b40，计划5af6672，红灯测试0f03b70。纯本地代码和隔离测试，不改132或共享服务，不接真实模型/凭据。

## 反例与修复

- narrow-red：H2新增7项全部失败（接受201/202而非400）；PG7项被跳过，独立Maven命令漏掉Docker API参数。不算PG通过。
- postgres-red：补本地非代理参数仍7 skip，诊断明确为Docker客户端1.32低于服务端最少1.40；保留该次退出0，仍不算通过。
- postgres-red-api144：使用正式门禁同样的`-Dapi.version=1.44`，真实PG7项全部失败且0 skip，均复现非法输入返回500而非400。
- 初始修复：共用TextInput显式检查，在各application入口、Conversation写入前调用；上传解码之后检查正文，检查文件元数据。H2+PG14项全部通过。
- 扩展：清点到直接Workflow试跑的input同样写TEXT，补engine前置检查及独立正反例；通用检查增加字符串/JSON单测。窄测共18项（H2 8、PG 8、unit 2）全部通过、0 skip。

没有修改持久化schema、SDK或依赖。检查不遍历DTO反射、不全局拦截Jackson、不把NUL从输入里偷偷删掉；公共错误不包含输入值。具体入口与未覆盖边界见`docs/spec/SPEC_INPUT_HYGIENE.md`。

完整门禁于2026-10-04T02:38:33Z完成：代码add75ed，schema3，harness/backend均passed，后端84类565项全部执行，失败/错误/skip/flaky均0；Harness Python54项通过。源码身份backend=5266955e、frontend=8f83bc47，受测源码diff为空。证据目录`harness/evidence/SPEC-INPUT-HYGIENE-001/SPEC-INPUT-HYGIENE-001-20261004T022129Z-d48cc760/`。

行为报告含68条路由、38功能组、44个具名子场景pass，不表示路由/功能全量通过。脱敏method-evidence离线重算JSON/MD逐字节一致，命令与SHA见reproduction.json。原始失败日志含合成SQL错误，仅提交脱敏计数和SHA，不提交XML或运行环境属性。未部署132。

独立静态复核无P0/P1（reviewer未运行）：Agent绑定写入口不在首轮实现中，PG可能500，且enabledTools/深层config集成覆盖偏窄，归SPEC-INPUT-HYGIENE-002补齐；检索query、memory/search与意图路由NUL反例归SPEC-INPUT-HYGIENE-003，不把现有门禁外推到读路径。复核消息om_x100b632f8debe8a4c1553576a7cc3e9。

最终复核消息om_x100b632f946e8cacc3295e305093a73：reviewer在d391d2b的archive隔离副本核对schema/head/565计数/源码树，复算tests摘要SHA及method-evidence SHA，并实际离线重算44子场景报告，JSON/MD逐字节一致；未重跑Maven。001按限定范围关闭，002/003分别处理登记项。
