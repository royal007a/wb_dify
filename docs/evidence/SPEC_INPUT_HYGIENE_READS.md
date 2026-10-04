# SPEC-INPUT-HYGIENE-003：读查询准入证据

基线2a9ca62，计划065fc87。仅本地H2及隔离真实PG；不用共享数据库/外部模型，不部署132。

## 反例

- d687592的首轮反例：H2/PG各4失败、0skip。活动知识query和意图agentId在PG返回500；memory的前缀NUL被原normalized().trim()去掉，返回200；冻结检索未抛预期PARAM_ERROR。不同旧行为不能都笼统称成500。
- 5eccf3c将中间NUL放在同组最前面再跑，H2/PG仍各4失败、0skip；PG三个HTTP入口实际500，冻结检索抛出数据库异常而非PARAM_ERROR。前缀/后缀负例仍保留，后续绿灯必须全部通过。

## 测试范围

- 四场景：活动知识、冻结知识的两种公开service、记忆search及entity、意图agentId/input；每个都有合法命中的正向对照。检索上传并等待DONE后确认文档ID和原文；记忆使用实际Mock Run的持久历史，非空匹配当前marker；意图SpyFactory在合法模糊输入确实被调用后清计数，再验证坏请求零调用。
- 适用的HTTP与直接service检查400/PARAM_ERROR、固定文案、前后五种表计数不变；冻结检索没有独立HTTP路由，仅测试两个公开service。冻结fixture在计数之前创建，不能把fixture写入混作读请求写入。
- 不证明Tomcat对%00 URI的拒绝，内部service ID保护和HTTP正文准入单独说明。没有真实供应商、浏览器或132效果验收。

修复后窄测8项全部通过（H2/真实PG各4），失败/错误/skip均0。完整backend/harness证据随后补齐；未完成时不宣称完整门禁通过。原始日志/XML不提交，仅保留SHA及脱敏计数。
