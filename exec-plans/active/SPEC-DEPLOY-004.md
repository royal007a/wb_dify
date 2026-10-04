# 发布产物迁移目标、索引静默及 smoke 清理

基线 b7b5595。只改本地 deploy 脚本及隔离测试，不执行132发布、真实systemctl/PG/nginx，不删除任何共享磁盘内容。既定验收scope=harness不变。

1. 对 smoke 的创建响应先登记可识别的自有ID（data字符串或data.id），再检查HTTP状态和契约形状。KB创建也纳入finally；无法识别ID时不猜测/枚举删除。清理逐项尝试，KeyboardInterrupt保持原类型和主因，清理失败单独可见。
2. 安装器的静默查询覆盖agent_runs.state、workflow_runs.status的RUNNING以及document_index_tasks.state的PENDING/RUNNING，停旧前和停旧后各检查。索引embed阶段仍是PENDING，不能只查RUNNING。假psql按FROM表名分别响应，三个类型有各自正反例；查询失败不视为0。
3. 从待替换Spring Boot jar内的SQL/Java迁移名称提取目标；校验格式、重复版本及完整序列，停服前发现损坏/未知格式/降级即拒绝。将迁移后检查从常量23改为该产物目标，不新增独立可漂移的手写版本号。仍由Flyway执行迁移与校验checksum，不声称预检模拟迁移。
4. 新测试先对旧脚本跑红灯，再实现。使用真实小型ZIP/tar与临时目录，系统命令全为假命令；在sh及可用的dash验证。未来V24正例、DB仍V23负例、缺失/重复迁移与三个迟到工作类型均覆盖。
5. 文档明确新的Python3前置依赖、产物布局和Java内部类处理、非标准响应清理边界。提交代码后runner完整harness门禁，保留失败及最终摘要，交mymacclaude独立复核。完整产品门禁与远端重部署仍待原阻塞解除。
