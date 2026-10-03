# Chat 生命周期复验

任务SPEC-CHAT-LIFECYCLE-002，基线76cf03a。仅~/hify，隔离Vite127.0.0.1:15174，浏览器全部HTTP/SSE受控，无共享服务/数据/凭据操作。

证据目录：`harness/evidence/SPEC-CHAT-LIFECYCLE-002/SPEC-CHAT-LIFECYCLE-002-20261003T230116Z-4da78444/`。

## 红灯与修复

red-browser.log：新增6项全部失败（实际到达功能断言，不是浏览器启动超时）：第二次创建时取消目标错误；CLARIFY后CANCELLED/TIMED_OUT残留resume；创建响应丢失无法同key恢复；第三次GET进行中到达终态丢失补读；成功重连不重置补读预算。

修复：固定待提交快照/幂等键，创建前清上一个Run，延迟取消只作用于本次身份；持久终态和continuation覆盖临时resume；补读中到达的终态记录待重读标志，重连成功重置断线预算。createRun不再内部生成随机key，调用方必须显式传入；create/cancel HTTP有10秒上限。

green-browser.log：原8项和新6项，14 passed（24.7s）。继续增加7个边界：创建Conversation时取消不提交Run；未知结果后取消沿用同key；持久空Gap不默默新建任务也不无限同步；持久Gap覆盖临时Gap；首次明确400拒绝可修正；先500后403仍保留未知结果；创建回包已经终态无需再等事件。

green-boundaries.log：21 passed（29.4s）。最终runner command.log再次21 passed（30.3s），源码18d2dee、测试/契约496aae5；无skip。浏览器完成后停止本次隔离Vite（确认命令及PID28142，仅此进程），15174无监听；没有停止共享后端。

## 范围限制

这是受控浏览器而非后端端到端。没有声称本片证明跨重启/多标签幂等、后端唯一恢复认领或页面刷新后的待提交恢复；详见契约。未部署，未调用付费模型。最终扩展矩阵与runtime/frontend/harness结果在runner完成后补记。

## 最终门禁（2026-10-04 07:06 CST）

verification.json headCommit=496aae5。浏览器21项、runtime34项全部通过无skip；frontend类型检查/生产构建、harness规格/状态/5项单测/Shell语法全部退出0。构建大chunk警告保留。已关闭本片runner并清理自有前端监听，独立复核待回；不代表全接口、全功能矩阵或部署已完成。
