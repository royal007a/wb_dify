# SEMANTIC-001：真实 Embedding 与有界精确检索

代码起点 47d8095；小库精确扫描补强见后续提交。没有宣称 ANN、大库吞吐或模型语义质量已全面验收。

## 已执行

- main compile、前端 typecheck 成功。
- 首轮 H2 窄测通过，真实 PG 首次被环境跳过；补 `api.version=1.44` 后又因继承 SOCKS 代理导致回环地址连接失败。这两轮不是 PG 通过。
- 加回环 nonProxyHosts 后真实 PG 4/4、0 skip；规模与兼容补强后 H2 7/7、PG 7/7，均零失败/错误/skip。
- 真实本机 Ollama bge-m3:latest（1024维）+ 独立 H2/28082：三个合成中文文档与改写问题，top1 均命中预期。脚本及结果位于 `harness/evidence/SEMANTIC-001/SEMANTIC-001-20261004T104417Z-61ed4dde/live-semantic-smoke.*`。该次 live 使用 47d8095 打包产物；不外推到后续规模补强。成功后仅清理自己创建的文档/KB/Provider。
- HTTP fixture 向量测试仅证明返回向量参与排名，不证明模型语义质量。真实三个问题同样不是全面 benchmark。

## 静态复核与补强

审查方只读确认 profile 固定、无 hash fallback、网络在事务外、归档乐观锁。所报整库向量扫描 P1 按小型库限定解决：512活动分块索引准入（KB锁串行化），查询和冻结在拉取向量前 SQL COUNT/SUM(OCTET_LENGTH) 检查512行/24MiB，超限409提示拆分；查询重复在读事务内检查避免竞争越过前置限制。一次冻结检索复用同一份语义向量进行摘要和排名；语义路径不再执行旧hash向量查询；混合空间拒绝。旧冻结摘要按旧 id:digest 字节序列回归测试。

## 2026-10-04 恢复执行的完整门禁

被测 HEAD `4fcd14a`，后端树与规模补强 `b399efe` 相同。证据目录 `harness/evidence/SEMANTIC-001/SEMANTIC-001-20261004T120832Z-2832db8a`：schema 3，invocation `4101454d-2b24-4bf2-87c1-3919c01023be`，2026-10-04T12:15:59Z passed。Backend 89 类/616 项、migration-postgres 15 类/125 项全部执行，failures/errors/skipped/flaky 均为 0；两个 scope 有重叠，不相加。Harness Python 74 项、前端 typecheck/build 通过。原中断 run 保留，不改写为成功。

以当前源码重新打包，在隔离 H2/28082 实跑本机 bge-m3:latest（1024 维）：三个合成中文改写问题 top1 均命中，查询 30–57ms。不是语义质量基准，也不是 PG 性能测评。脚本只清理自建数据和自己的 Java 进程。真实 PG 另由上述门禁验证，Dify 未停止。

提交脱敏 method-evidence.json，显式 `--method-evidence` 离线重算得到 57 个已映射子场景 pass、0 fail/not-run，JSON/MD 与直接 XML 生成版本逐字节一致。原始日志/XML 未提交，源码树和空源码 diff 摘要见 source-identity.json。报告不是全部接口行为通过证明。

## 尚待

后续 Workflow 扩展和132部署证据仍待完成；本片本地验收完成不代表已部署。Provider 故障/在途归档专用竞态测试未补；外层事务拒绝、共享熔断器、逐批预算、关停可能FAILED及固定凭据引用边界见 API。
