# 规格证据等级与机器测试摘要

后续于 a8f5023..a2f05a0 的只读review，任务状态只看tasks.json。

1. CURRENT_STATE逐处标明源码/专项测试/历史运行；本轮backend的PG跳过不能借历史结果代替。FUNCTION_TESTS记录候选测试时写受控时序、mock和未测边界。
2. 从本轮日志抽取逐测试类的tests/failures/errors/skipped、源码commit、命令和日志SHA，提交可复核摘要。历史原始manifest不改写。
3. 为后续Harness记录新增机器测试摘要，显式区分command exit与coverage；如果声称完整backend/PG验收，就必须配置Docker并拒绝skip，不能令partial变成passed。新规则要有红灯/单测，不顺手放宽其他权限或门禁。
4. 反例索引补具体未解路径：知识完成事件、恢复预算与计数/UUID、拒绝后写库失败、104容量、孤儿整体写失败、子任务顺序、命令行密钥、END/父终态、旧表达式兼容、知识连接检测与checksum、SSE慢读和熔断采样。旧段注明基线/指向后续，不做第二份任务状态表。
5. 功能规格补缺失负路径：逐接口400/404/405/409/413/415/503；重复/终态取消；拒绝后202+FAILED、重放200；lookup只读/no-store。跑制度门禁并交review；全部业务是否满足仍由SPEC-VERIFY-001逐项记录。

不修改hify-cc、生产行为、迁移、共享服务或132；不读取密钥。证据日志默认仍不入库，只提交脱敏的计数/类名/摘要与复现命令。
