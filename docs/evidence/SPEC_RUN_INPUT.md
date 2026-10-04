# Run输入、完整性错误与失效resume（2026-10-04）

基线c837cb1，计划2ae3c03，红灯测试be93508。仅原版hify；未部署，未触碰132/hify-cc/真实凭据。

## 红灯与修复

`mvn -B -f backend/pom.xml -pl hify-app -am -Dtest=RunSubmissionIdentityTest -Dsurefire.failIfNoSpecifiedTests=false test`：旧实现11项，2 failures/1 error/0 skip。超长20001输入与无关约束均为409而非400/500；读会话后另一连接删除，Future内断言404但实际409。原H2同key两线程竞争用例通过，作为正向对照。

修法：HTTP `@Size(max=20000)`及service同样按Java UTF-16长度预检；仅SQLState23505加精确uq_run_idempotency允许失败事务回滚后的幂等重放。H2实际异常使用生成索引PUBLIC.UQ_RUN_IDEMPOTENCY_INDEX_*，只在实际H2 SQLException类型下识别该索引，非任意错误正文匹配。其他完整性失败，事务回滚后会话不存在返回固定40400，否则固定50000；不回传/记录SQL、约束名或提交正文。未修改schema；非并发入口的历史会话不存在400语义未统一。

修复后相同窄测11项通过；新增service直接调用超限断言随最终门禁重跑。并发删除使用latch暂停在已读会话后的Provider准入，主线程通过另一连接提交删除再放行，断言Run/消息/事件计数不变、executor零调用；不是仅伪造外键异常。无关唯一约束一项是合成异常，不能代替真实PG竞争证据；PG既有八请求同key用例由全量门禁另跑。

浏览器新用例先失败：旧页面继续保留失效resume且允许发送。修复后，只有此前非unknown的明确4xx拒绝才清除resume并禁用继续发送，提示新建会话；不自动恢复该次澄清原文，不暗中转成新任务。此前unknown仍保留key找身份。用户另行编辑的草稿不被覆盖。

`E2E_BASE_URL=http://127.0.0.1:15174/ npx playwright test e2e/chat-lifecycle.spec.ts --workers=1 --trace=off`：32 passed（49.9s），其中新增1项。真实Vue/浏览器、HTTP和EventSource受控；不是后端SSE或网络故障验收。独立Vite已停止，没有改共享服务。

## 证据

证据根`harness/evidence/SPEC-RUN-INPUT-001/SPEC-RUN-INPUT-001-20261004T010956Z-64e536c5/`。原始日志不提交，以下SHA只能核对本地日志，不能还原日志。

| 文件 | SHA256 |
|---|---|
| backend-red.log | 9f891093cb7b0e11be60b73d58ea7101353448fb4d007b64c5d43ddea4d0ea52 |
| backend-green.log | c59600c2791a75b4eb512534d2c36b7e1f1c75d09a006e162e423587571330b4 |
| browser-red.log | 56e702a1b8360af52d985fc4280264a462fb2d1bd28f3291983ba8d1e1f30051 |
| browser-green.log | 8d3b9465f578177900d1a5b31741866fcd9d31dc4a33afe2da141b952b61a2ca |

最终harness/backend/frontend门禁另记。输入UTF-16单元不是Unicode码点或UTF-8字节；400/404/500不承诺所有数据库故障均有相同分类。当前没有ACL，页面新会话不等于撤销服务端历史任务。
