# Chat 恢复残余交互

基线5555b95。验收仍为frontend/backend/harness，不删除后端范围；已知Colima盘满，完整backend门禁须待环境恢复，不能以H2或受控浏览器代替完成。

1. 补确定性反例：GET被路由门闩挂起时，连续短断线暂停后人工重试应能重建一次同Run SSE；旧流/重放不重复，终态与在途GET交错只交付一次。按钮允许排队一次重试，不让loading吞掉意图。
2. 取消待确认显示“查询取消结果”，沿用原GET lookup路径，不POST。保留明确拒绝resume后清除上下文且只能新会话恢复的现有用例。
3. 缺失和跨会话resume来源统一40000固定文案，无ID回显，在任何写入之前拒绝；同会话不在NEEDS_INPUT仍409，合法恢复与幂等语义不改。HTTP/H2统计零增量及正向对照，服务层使用统一BizException/ErrorCode。
4. 先保留红灯，再实现；本地独立Vite/受控浏览器，不用共享后端；仅选定H2测试，不启动Docker或PG。规格、候选映射、最少用例数和证据同步。
5. 单独记录frontend/harness门禁及浏览器/H2范围。若盘仍满，runner非零保留blocked，不跑明知无空间的全后端容器门禁；待完整三scope通过再收口、再评估部署。交mymacclaude只读复核，声明未测与未部署。
