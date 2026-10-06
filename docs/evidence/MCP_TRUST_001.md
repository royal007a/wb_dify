# MCP-TRUST-001：原版 Hify 独立 JVM 信任库

2026-10-06，用户选择方案②，授权 `om_x100b637e5c2f24a8c4f563a9c3596cd`。执行代码 `f708f1b`；原版线上 jar **未替换**，仍为此前 a510191 源码树构建的产物，不包含尚待发布的 H1/聚合/结构化输出更新。

## 已执行与结果

`./harness/run-task.sh --approval-ref lark:om_x100b637e5c2f24a8c4f563a9c3596cd MCP-TRUST-001 -- python3 harness/mcp-trust-rollout.py`

通过独立 persistent oneshot `hify-mcp-trust-20261006` 执行，最终 Result=success、ExecMainStatus=0、active/exited（RemainAfterExit 保留）。服务 PID 从 3218119 变为 1650016；Hify active，后端 health 200，另行使用已信任公开证书验证 `/hify/` 页面 200。

- 专用信任库：`/etc/hify-trust/workbench-20261006.jks`，root:root 0644，目录0755。只含公开证书；没有放宽 `/etc/hify` 的0700权限。
- 独立配置：`/etc/systemd/system/hify.service.d/40-workbench-trust.conf`，root:root 0644，仅设置 JAVA_TOOL_OPTIONS 中的 trustStore 路径和 JKS 类型。原进程不存在 JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS，也无显式 trustStore 参数，因此未覆盖既有选项。重启后的进程环境精确匹配新增选项。
- 原系统 cacerts 的146个证书 SHA集合全部保留；新集合147个，新增仅为工作台证书。原系统 cacerts SHA未改变；没有私钥条目。
- 导入前比对 SSH 主机上 `/data/certs/self.crt` 与此前已经记录的固定 SHA，导入后再核对实际 TLS 对端证书：`e1bb50bd7d35af61eb0bd9f921f8fab8ad61c7b521c67034e875b22d0e491ab5`。该证书 SAN 有 IP `118.196.123.132`，不是把本轮首次网络所得证书直接无条件加入。
- Java17 JSSE 正反探针：默认库拒绝自签握手；专用库在 hifyapp 身份下、启用 HTTPS endpoint identification 时成功，匿名 GET `/api/v1/mcp` 返回401；使用错误主机名 `invalid.hify.example` 仍然握手失败。重启后再执行一次同样的可信握手成功。
- jar SHA保留 `5ee59af0c9b13bb6cfe62f700813a117f4492268641cb153486ff121c4b1f230`；主密钥仅比较 inode/size/mtime/mode/owner，未读值、未改变。
- 没有改 nginx、CC、工作台、数据库、Token 或工具只读标注，没有调用认证 MCP 或模型。远端最终可用1507856KiB，97%；没有清理共享文件。

## 证据与验证范围

目录：`harness/evidence/MCP-TRUST-001/MCP-TRUST-001-20261006T072554Z-b9c367e1/`。

| 文件 | SHA256 |
|---|---|
| trust-result.json | b2bbb7c73f30031eb13072a69141f7fdafc68b3a05b6610b3240166cd634ae55 |
| verification.json | b95713c4d8a4136053fc9aa143b67c097d8e62b4f88ea399be624bef7ebb1721 |

配置/文件摘要：trustStore `3b10a369448659e819d0ea707e35ac19b0d695db4bab9e3fee37d4ad44e4d5f5`；drop-in `78e800ecfac623fc391f9d0f1ed899c9e51d5289a2a73a0095bf95daefaaa559`；默认 cacerts `386bd64760f18f552cf8a9d9809f51554a26d61e8f8e716e1510f7b0ba2aa5ed`。

本次 schema3 **harness scope** 五步 passed，07:28:05Z完成。原始本地日志记录 Python78项通过（107.098s）；便携verification只记录该命令exit0和日志SHA，不含结构化计数，原日志未提交。另外执行前的安装器/上传隔离夹具18/18（119.585s），只用假系统/网络命令。没有重跑 Maven、全产品浏览器或认证工具调用；不能以这些检查替代产品完整门禁。

源码依据：线上 a510191 的 `McpProtocolClient` 使用 `HttpClient.newBuilder().build()`，没有配置自定义SSLContext。探针是独立JSSE进程，结合运行中Hify已收到JVM选项确认配置，不冒称实际 Agent→MCP 已验收。用户需在页面重试“发现工具”；工具缺少 readOnlyHint 而被归类EXTERNAL的发布限制不在本任务修改范围。

静态复核后补做公共CA实握手：`PublicTrustProbe.java` 在远端以 hifyapp 身份、上述新信任库JVM属性和默认SSLContext连接 `ark.cn-beijing.volces.com:443`，HTTPS主机名校验开启，握手通过、exit0。未发送HTTP、凭据或模型请求，没有费用调用。结果见`MCP_TRUST_PUBLIC_CA_20261006.json`。该补充探针在首次harness门禁之后单独编译执行，不能称为原门禁中的用例。

## 回退及维护

脚本在停服务及配置应用部分失败时，会把**本次唯一新增**的 drop-in 移到 `/opt/hify/releases/mcp-trust-20261006/failed-40-workbench-trust.conf`，daemon-reload 后尝试按原配置重启并检查health；原JDK库不修改，专用公开证书文件保留诊断。该失败回退分支本次未实际触发、未注入故障演练，不宣称回退已实测。

需手工回退时，先核对 drop-in SHA仍等于上表、三类任务静默、恢复目录目标不存在，再执行以下明确作用域的操作；后续若配置有变，不可照抄：

```sh
mv /etc/systemd/system/hify.service.d/40-workbench-trust.conf /opt/hify/releases/mcp-trust-20261006/manual-40-workbench-trust.conf
systemctl daemon-reload
systemctl restart hify
curl --noproxy '*' -fs --max-time 10 http://127.0.0.1:28080/api/v1/health
```

回退后将再次不信任工作台自签证书，不涉及数据库恢复。新信任对**整个 Hify JVM**生效，不限于单 endpoint；JDK公共CA更新需重新合并，工作台换证书需重新核验，不会自动接受任何新证书。

补查公开证书的 Basic Constraints 是 critical、CA:TRUE：该证书作为进程级信任锚，理论上也能信任它签发且主机名等校验通过的其他证书，并非只豁免132这一张叶证书。持有该CA私钥的一方应严格保护私钥。若需要仅限工作台目标，必须另做endpoint级SSLContext/证书固定；本任务没有实现这个更窄的范围。

仍有运维边界：三表检查不是阻止新请求的准入锁，检查与stop之间有空窗，晚到运行可能被中断；stop后再次检查只能发现遗留在途并进入恢复，不能承诺零中断。创建信任库阶段失败尚未包在回退try中，可能只留下journal/文件而无result.json；禁止直接重跑或删除后硬重试，应先检查。恢复分支目前只记录health，未自动核对旧进程选项，且未故障演练；维护人员必须补查。JKS类型检查不匹配会在停服前安全失败。负例只校验SSLHandshakeException类型，没有分类具体证书异常原因。
