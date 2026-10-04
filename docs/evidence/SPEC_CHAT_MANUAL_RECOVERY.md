# Chat 人工恢复与建会话超时

任务SPEC-CHAT-LIFECYCLE-004，基线`e48928c`（生产代码同`f17ab2a`）。证据根`harness/evidence/SPEC-CHAT-LIFECYCLE-004/SPEC-CHAT-LIFECYCLE-004-20261004T001246Z-fa30e7f6/`。不部署132、不访问共享数据库/真实模型/凭据。

## 红灯与修复

- 浏览器4个反例在未修复的生产代码上全部失败：人工同步仍只有1个EventSource；放弃resume后恢复澄清原文；取消待确认显示成重发；会话创建挂起超过15秒仍无可放弃出口。日志`browser-red.log`。不是启动失败，也不是测试空断言。
- 后端5项中2失败：by-key缺header的400无no-store；新key+不存在的resume返回40900而非400。后者确实是新发现的生产错误：先INSERT触发FK_RUN_RESUMED_FROM，被宽泛的DataIntegrityViolation竞争处理误归类；不是“原测试断言写错”。恢复来源/Gap/checkpoint构造改在任何Run/消息写入前，持久化仍在同一事务；同key查重顺序与40901不变。

## 方法级证据

| 路径 | 断言及边界 |
|---|---|
| repeated opens… | 6次短断线暂停，人工按钮新建同Run流、状态补读；旧流回调不污染；重复ID不再拼接，新增delta拼到原气泡；新流再次6次失败仍暂停；Run POST恰1次 |
| abandoning an uncertain clarification… | 正向得到NEEDS_INPUT及持久Gap，提交确实携带resume；网络失败后放弃不恢复澄清原文，提示上下文断开，空输入不允许运行；不自动POST |
| cancel intent remains explicit… | POST在途点击取消，再模拟网络失败；文案为仅查询，按钮GET，POST恰1次 |
| hung conversation creation… | 真AbortSignal 10秒期限（未替换时钟/超时函数），15秒内可放弃；明确尚未提交Run；断言页面EventSource数和Run POST数均0。abort后对已结束请求的fulfill会失败且被忽略，不算“成功交付迟到响应”的证据 |
| RunSubmissionIdentityTest | 同key不同resume40901；新key完全相同非法载荷40000；Run/消息/事件数不增、executor恰1次。by-key缺header400也有no-store/Vary |
| RunFlowIntegrationTest | 既有2项继续通过，覆盖实际完成和澄清恢复控制流 |

浏览器命令`E2E_BASE_URL=http://127.0.0.1:15174/ npx playwright test e2e/chat-lifecycle.spec.ts`：31项通过，49.2秒，退出0。隔离15174 Vite、真实Vue/Chromium，HTTP/EventSource均受控，不是服务器SSE/代理验收。相比原28项新增3项并加强原1项；并未删掉原先的终态补读/同key/取消回归。

后端命令`mvn -B -pl hify-app -am -Dtest=RunSubmissionIdentityTest,RunFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`：2026-10-04 08:14:55 Asia/Shanghai退出0，7项、0失败/错误/跳过，全应用MockMvc与隔离H2，非PG或真实网络。两份红绿日志SHA与计数见focused-summary.json。

完整harness/backend/frontend门禁于2026-10-04 08:19:03 Asia/Shanghai结束，代码`83055fb`，schema v3/passed：backend 81类536项、失败/错误/skip/flake均0（含真实PG专项）；Harness Python 31项通过，frontend typecheck/build退出0。浏览器窄测不代替该门禁，backend/runtime/migration重叠用例不累加。浏览器绿灯之后只调整了bindConnection缩进，没有改变语义。

全量开始前，backend/frontend没有未提交或未跟踪源码；source-identity.json记录两个Git tree、空diff SHA和仅Harness状态/证据的status清单。本轮verify仍写base=working-tree，不将其说成隔离的干净clone。独立15174 Vite已停止；没有部署。

## 保留边界

请求超时/放弃只终止本地等待，不是服务端撤销；会话创建无幂等键，丢响应可能留下空会话。新EventSource从头重放，用原seen集合去重，不宣称跨刷新持久游标。任意多次人工重试仍由人发起，每次自动尝试有上限；未实现全局次数配额。跨标签、不同key恢复同一澄清的后端一次性认领不在本片。

独立review静态阅读代码并复算提交中的测试摘要SHA/tree（未重跑），接受上述五条P2和非法resume修复。另有旧P2：create对所有DataIntegrityViolationException都尝试幂等重放，20001字符消息/并发会话删除的错误仍可能误归40900；由SPEC-RUN-INPUT-001另行跟踪。还有人工同步遇到在途GET需再点、查询按钮文案、明确拒绝resume后的循环等P3；不随本片关闭。
