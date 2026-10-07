# CONTEXT-ROLLOUT-001：原版 132 发布记录

2026-10-07 21:21:33 CST，发布 runner 自然退出 0。入口：
https://118.196.123.132/hify/ 。这是发布方的运行观察；独立证据复核待进行。

## 授权与固定范围

- 用户授权 `lark:om_x100b63505002e8a0c367a18a505de0e`（“a+ b”），对应前文
  `om_x100b635055806134b3bd189df71c88b` 的 B：允许 nginx reload，知悉换 jar 后失败可能停服、需要人工恢复。
- 发布 HEAD `f67a79171323b6a131a777bb7f8b42fbe98751cb`；产品源码绑定
  `bc4ab0e4afe39939f9bfe5c6bb9a3797d6ebbb50`，不是将全部研究候选部署。
- 原六范围 gate `OBS-CORRELATION-001-20261007T114929Z-298dada4`，verification SHA256
  `45fdd23440f086300194601960c8d5c69054fe852fc4e9d740c4dd8df93e3704`。
  backend 796、migration 126、runtime 35、eval 24 均零失败/错误/跳过/flaky；范围重叠不合计。
- backend/frontend/deploy 树分别为 `c6bdcd259607ba5fed44ff68a7346e26883fb87f`、
  `931a8823bf0513777fc88f0d3447daef94e0e71d`、`646016ba3f4383b0474cd8dec1d6c966f34b305c`。

原始记录目录：
`harness/evidence/CONTEXT-ROLLOUT-001/CONTEXT-ROLLOUT-001-20261007T130420Z-d2e458aa/`。
命令见 run.json；开始 13:04:20Z，结束 13:21:33Z。仅原版 `/opt/hify` 及其 nginx snippet；
执行 nginx -t/reload，没有修改 hify-cc、工作台、Dify，没有清理共享缓存。

## 发布观察

| 检查 | 记录 |
|---|---|
| 只读 preflight | free 2997648 KiB ≥ required 2419847 KiB；含 2 GiB 余量 |
| 释放目录 | `/opt/hify/releases/spec-verify-20261007-context-bc4ab0e4` |
| 数据库备份 | 目录内 database-before.dump，501282 字节、0600；pg_restore --list 成功 |
| 回退材料 | previous.jar、previous-dist、previous-nginx.conf 保留；未执行回退或恢复演练 |
| 运行身份 | PID 1650016 → 3696955；启动 tick 326254019 → 336977771 |
| JVM jar | cmdline、打开的 jar FD、磁盘 SHA 一致；不是逐个已加载类字节校验 |
| 健康/数据库 | service active、health 200、V1–V24 全部成功；三类在途计数均 0 |
| 密钥 | 前后 stat 元数据相同；未输出值 |
| 磁盘 | 发布后 available 2763708 KiB，已用 93% |

新 jar SHA256：`bc84981a02f776374a140d08b78fd6e0290a38095d816cde4256c88cdbc03ee2`。
index.html：`c273202c61beed5c8dd8de639f660f51feb8b61e5fab6a80d06212d1aa7f6238`。
前端共 3 个本次产物均与远端逐文件 SHA 一致；浏览器实际加载的 2 个 JS/CSS 响应也核对过。
完整清单在 local-artifacts.json 和 browser-live.json。

## 验证与限制

- current API smoke 23 条、prefix/direct 各 5 条通过，分别记录，不冒充互不重叠的独立用例。
- 部署态浏览器 2 会话、5 Run 全部 COMPLETED；逐轮核对版本、streamUrl、持久化和页面答案。
  显式使用 Demo Mock，没有付费模型调用，不证明真实模型、embedding 或新 Workflow 的质量。
- 自建 Agent/KB/document 归档；测试会话/Run 因无删除接口保留，ID 在 JSON 内。没有删除用户数据。
- 本次从合格源码重新构建，不是六范围运行时的同一 jar。前端发布构建使用 `/hify/` 与 `/hify/api`；
  runner 收尾另以默认 base 构建并覆盖本机 dist，**后者没有上传**，不可拿当前本机 dist 当发布清单。
- 收尾 verification schema 3、仅 harness/frontend 两范围，通过；Python 原日志 93 项 OK，7 个步骤 exit 0。
  SHA256：`2cdf63294843fd91d5c0b47a33bb7750b529bbd35ace4d08a9be5b101ea6d279`。
  这不是重新执行后端六范围。日志中的 Maven evidence: failed 是 Harness 反向夹具输出。
- 自签名 smoke/browser 的测试客户端关闭证书校验，应用出站 TLS 配置未改变。
- nginx -t 有既有 workbench 重复 MIME warning，语法检查仍成功；本轮未修工作台配置。
- 备份仅可列出，未验证恢复；没有自动回滚。此次各检查通过，没有触发失败停服分支。
- 来源降权设计仍未实现；新设计评审通过不等于上线。旧慢启动与超时红灯保留，未宣称根因消除。

收尾步骤原日志经敏感模式检查后随证据保存；SHA 证明所存字节一致，不构成独立执行证明。
