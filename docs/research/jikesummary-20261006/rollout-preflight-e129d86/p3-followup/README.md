# 发布 P3 窄补强（不修改控制器或安装器）

输入仍为 e129d86/bc4ab0e 的 controller，同一内容已经通过新六范围。
本目录不属于该次六范围的用例计数，是门禁之后单独执行的证据。

## 新的只读远端探针

readonly-probe/command.sh 在本次执行前保存，其字节作为 ssh stdin 下发；
invocation.json 记录 argv、开始时间与脚本 SHA，result.json 记录退出码和输出哈希。
这里只含 set -eu、服务状态、共用 running_identity 探针与 df；无文件上传、配置写入、重启。
此次成功观察 PID/启动 tick/jar SHA 与上次一致。不是事后补造上一次调用的命令证据。

## 从生产代码抽取的窄检查

`python3 guards/check_guards.py harness/context-rollout.py <mode>` 在仓库根执行。
green exit 0；pid-mutant、tick-mutant、budget-mutant 各 exit 1 且 AssertionError。

- PID 判定：不同的单个 PID 接受；相同、缺失、重复拒绝。
- 启动 tick：单个且递增接受；相同、回退、缺失、重复拒绝。
- 预算：从 preflight 抽取生产算式和比较语句，在纯算术 shell 中验证
  staging + old + ceil(database bytes/1024) + 2097152 的少 1、恰好、多 1 边界。
- 突变只在隔离表达式中将 PID/tick 守卫替换为 True，或移除 2 GiB 保留项。
  每种缺陷都会违反对应 oracle；没有修改生产文件，也没有实际部署。

这些不是端到端进程替换/磁盘耗尽测试，没有覆盖每个探针失败时点。
未新增探针中途进程变化、运行 FD 与磁盘不符、PID=0 或预检/建目录换序的独立突变。

## 保留边界

- 新产物大小用 st_size，旧产物用 du 实际块；小文件块取整可能低估新包占用，
  2 GiB 是安全余量，不是精确峰值测量。每次部署仍按 requiredKiB 拒绝不足。
- finally 已用 replacement 解码保存观察 JSON。成功命令后的另一些读取仍严格 UTF-8，
  如遇异常字节可能让后续校验失败；之前 finally 已写出的 JSON 和原日志仍保留。
- 安装器只发布 assets/ 和 index.html；当前构建布局符合，但未来增加其他顶层静态文件时
  必须同步修改安装契约，否则全清单校验会在安装后拒绝。本轮未实现通用静态目录发布。
- 没有风险确认不切换；本目录不授权 reload nginx、清理共享环境或自动回滚数据库。

## 独立复核后的证据限定（2026-10-07）

mymacclaude 只读核对 `6ae5b3d..55b6591`，报告 SHA256SUMS 的 11 项一致，
command.sh 除末尾 df 外与生产共用探针逐字一致，三个突变按断言退出 1。
该结论不等于对方执行了 SSH 或突变。

- argv 原文在 invocation.json，由 SHA256SUMS 间接覆盖，没有另存 argv 专属摘要。
- “下发前保存”是执行方过程记录；仓库里的 startedAt 和文件本身不能独立证明时间先后。
- 突变实际执行使用临时目录中的脚本副本，没有保留该副本与提交版逐字一致的独立证据。
  日志中的 controllerSha256 只证明记录所指向的控制器内容，不能升级为完整构建链证明。
