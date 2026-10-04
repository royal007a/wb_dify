# SPEC-INPUT-HYGIENE-001证据

基线19a1b40，计划5af6672，红灯测试0f03b70。纯本地代码和隔离测试，不改132或共享服务，不接真实模型/凭据。

## 反例与修复

- narrow-red：H2新增7项全部失败（接受201/202而非400）；PG7项被跳过，独立Maven命令漏掉Docker API参数。不算PG通过。
- postgres-red：补本地非代理参数仍7 skip，诊断明确为Docker客户端1.32低于服务端最少1.40；保留该次退出0，仍不算通过。
- postgres-red-api144：使用正式门禁同样的`-Dapi.version=1.44`，真实PG7项全部失败且0 skip，均复现非法输入返回500而非400。
- 初始修复：共用TextInput显式检查，在各application入口、Conversation写入前调用；上传解码之后检查正文，检查文件元数据。H2+PG14项全部通过。
- 扩展：清点到直接Workflow试跑的input同样写TEXT，补engine前置检查及独立正反例；通用检查增加字符串/JSON单测。窄测共18项（H2 8、PG 8、unit 2）全部通过、0 skip。

没有修改持久化schema、SDK或依赖。检查不遍历DTO反射、不全局拦截Jackson、不把NUL从输入里偷偷删掉；公共错误不包含输入值。具体入口与未覆盖边界见`docs/spec/SPEC_INPUT_HYGIENE.md`。

完整backend/harness门禁、源码身份与报告随后附本任务独立evidence；没有完整结果前不宣称任务通过。原始失败日志含合成SQL错误，仅提交脱敏计数和SHA，不提交XML或运行环境属性。
