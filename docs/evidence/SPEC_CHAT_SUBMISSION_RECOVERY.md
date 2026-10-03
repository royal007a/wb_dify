# Chat 提交身份恢复与安全退出

任务SPEC-CHAT-LIFECYCLE-003；基线a2f05a0。后端修复与真实HTTP测试
`1ce9cfb`；客户端修复`6a0769a`。原版Hify，不涉及hify-cc。
证据目录：`harness/evidence/SPEC-CHAT-LIFECYCLE-003/SPEC-CHAT-LIFECYCLE-003-20261003T231505Z-3104ff49/`。

## 反例与结果

| 验证 | 旧实现 | 修复后与范围 |
|---|---|---|
| RunSubmissionIdentityTest（Spring/HTTP/H2） | 3项失败：停用Provider后同key重放实际409；新GET缺失返回404；无no-store | 3项通过；真实创建事务已提交，修改隔离库Provider.enabled并清缓存；重放200/异体40901/新key仍409，无第二条消息/事件/调度；只读GET查找不执行，跨会话/未命中404、非法key400、响应no-store/Vary |
| 5个受控浏览器反例 | 全部失败：取消未知提交仍二次POST；400丢输入；永久403无放弃出口；查询未使用；短断线不关闭 | 首轮完整24项通过（33.4s）；不是后端和真实SSE测试 |
| ApiContractInventoryTest + 上述HTTP测试 | 新端点未在旧库存 | 4项通过，Spring显式method/path/handler与68项规格一致；库存不证明68项行为全覆盖 |

窄命令：`mvn -B -pl hify-app -am -Dtest=RunSubmissionIdentityTest,ApiContractInventoryTest -Dsurefire.failIfNoSpecifiedTests=false test`。
浏览器命令：`E2E_BASE_URL=http://127.0.0.1:15174/ npx playwright test e2e/chat-lifecycle.spec.ts --workers=1`；Vite为独立15174端口、真实Vue/Chromium、HTTP/EventSource受控、trace关闭。红灯只选择5项；其中缺少放弃按钮导致Playwright等待超时，其余为具体断言失败，不是启动失败。`red-backend.log`、`red-browser.log`保留本地原始输出。

## 语义与未保证

- POST重放按原body摘要验证，但已存在记录不依赖当前Provider启用状态；新工作仍校验。新增GET使用header key且no-store，key不写URL。未来ACL必须同时保护查询/重放/创建。
- 取消未知请求不重新POST。GET404只表示此刻未找到，原POST可晚到；页面明确未确认取消、允许重查或显式放弃。不实现持久化取消墓碑，不承诺撤回在途请求。
- 放弃只解除本地等待，脱离旧会话且不自动重发；警告后台可能继续。刷新/离页也不保存outbox。多标签新key仍可重复工作。
- 明确4xx保留用户已改草稿，否则恢复原文；重复未知错误只更新一个事件。放弃按钮仅在无在途请求时可用。
- 连续6次短断线无新业务进展时暂停自动流/补读；稳定连接至少15秒或新的已处理业务事件重置计数。HTTP200、重复ID和初始heartbeat不重置。人工同步只GET，不重发用户消息；不伪装终态。
- 不改迁移/唯一键/调度/模型执行；PG、反向代理、付费Provider和生产部署不在本片窄测试中；完整PG零skip由SPEC-VERIFY承担。所有测试数据库及浏览器响应均合成数据，不读真实凭据。

## 最终门禁

`daec369` 最终门禁：backend 78个测试类、498项，其中409实际运行通过、89跳过，失败/错误0；runtime另跑34项、零跳过。Harness各步骤、前端typecheck/build退出0（构建仍有大于500KiB的chunk警告）。原verification.json的passed仅表达所选命令退出0，不能当作PG或全行为通过；migration scope本片未跑。扩展浏览器28项通过（34.4秒），包含晚到原POST、跨会话错误响应、稳定连接、重复事件，仍是受控HTTP/SSE。

2026-10-04独立静态复验关闭原P1和三个P2；reviewer未跑测试。新增P2：暂停后人工同步不重连SSE、放弃resume会恢复失去上下文的文本、取消意图在未知错误文案中丢失、createConversation没有超时。另缺不同resume同key的后端断言，缺header的400未带no-store。这些登记为SPEC-CHAT-LIFECYCLE-004，不把本片关闭外推成全部Chat边界完成。未部署132。
