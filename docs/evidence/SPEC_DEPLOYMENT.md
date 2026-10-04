# 132 原版 Hify 发布验收（2026-10-04）

授权：goal:01a07e90-58c6-7f00-8ce1-fc659dd6c39c。仅操作原版Hify的systemd服务、数据库备份/迁移、jar/dist及`/etc/nginx/snippets/hify-path.conf`；没有修改hify-cc、共享TLS证书、其他应用location或真实凭据。

## 基线、产物和恢复点

- 发布前：d06034e，jar `299838bdd9b7fddcb4d7554e2f06099aec6d14e38c49181829a152f69801a667`，Flyway V20，32条Run均COMPLETED；0条Workflow执行。没有在途Run时停止旧实例，不双开。
- 构建提交a66be9e（应用/前端源码树与9f40639完整门禁相同）；`mvn -B -DskipTests -f backend/pom.xml package`及`VITE_BASE_PATH=/hify/ VITE_API_BASE_URL=/hify/api npm run build`退出0。skipTests仅用于打包；测试证据由独立门禁给出，不把打包成功当测试通过。
- 远端jar和本地一致：`fa44c40e4270506ddfbf11aaa0020e2915b1c9bc976022d3a1369a899659e7bc`；index `316e07fc2676e8f73b9abcc71fcc4e18a0711a6f485d3ec5d89d6aea0e5c5e3e`；Hify nginx片段 `6c43b0a0f431ac4fa27ff0f2d48e71c02e4ce15a5aa607dbbc2714d0cf132281`。
- 恢复点：`/opt/hify/releases/spec-verify-20261004-a66be9e/`，目录0700；previous.jar、previous-dist、previous-nginx.conf与停止旧写入进程后生成的database-before.dump。dump为0600、301080字节，pg_restore --list成功；未执行恢复演练，不能称数据恢复已验收。
- 主密钥文件仍root:0600，升级前后inode/size/mtime/权限/所有者一致。脚本不读密钥内容、不生成或覆盖密钥。凭据值、授权头、数据库dump不放Git。旧env引用未自动授权；teacher_mcp仍为旧环境引用，管理员需按授权策略处理或经页面重新输入Token；不把引用名当密钥。
- V21/V22放宽Workflow状态约束、V23新增可空恢复列；升级成功后不能不看新增状态就盲退旧jar。安装器故障会保留DB/key，迁移后故障停止新服务等待评估，不自动恢复数据库。保留旧hashed assets，index最后替换。

## 运行验收

入口：`https://118.196.123.132/hify/`。systemd active，内外health为HTTP200/code200，V21/V22/V23均success=true。nginx -t通过后reload，专用片段的12m与JSON41300生效，没有修改其他应用的`/api/`。

1. `deploy/smoke-upload.py`在132分别直连28080、通过`/hify/api` HTTPS执行：未知Run404；创建独立知识库；2MiB、恰好10MiB上传202并回读正确fileSize；10MiB+1、13MiB返回413/JSON41300；每个成功文档只产生一个合成分块，真实PG检索返回候选。测试文本是短句加空格，证明字节边界/入库路径，不是大语料索引性能或语义准确率。两路都通过，自建文档和知识库均归档；SQL确认自建活跃KB和文档均0。没有删除已有用户数据。
2. 真实132浏览器：`E2E_BASE_URL=https://118.196.123.132/hify/ E2E_IGNORE_HTTPS_ERRORS=true PLAYWRIGHT_CHANNEL=chromium-headless-shell MCP_TOKEN_LIVE=1 npx playwright test e2e/chat.spec.ts e2e/chat-time.spec.ts e2e/mcp-token-live.spec.ts --workers=1 --trace=off`：3 passed，17.7秒。Demo greeting/三次时间/calculator共5条Run，完成数从32到37；Token合成值保存、KEEP、替换、CLEAR及不回显通过，测试Server归档。没有调用真实LLM或teacher MCP。
3. 证书仍自签名：严格curl验证先返回60；HTTPS smoke和浏览器显式接受测试证书，证明TLS路径/代理行为，不证明公有CA受信。未弱化生产HTTP客户端的TLS或出站策略。
4. 磁盘发布前2086296KiB可用，验收后1858008KiB可用（96%使用）。nginx请求体目录`/var/lib/nginx/body`为www-data:0700且在同一磁盘。容量仍紧张，需要日常清理/扩容决策；未擅自删历史备份或其他应用数据。
5. 132 HTTPS按不存在的会话和合成Idempotency-Key查询by-key：HTTP404、JSON40400、Cache-Control:no-store、Vary:Idempotency-Key。此检查只验证负例响应；只读零写入断言仍由本地集成用例证明，不把一次curl观察当全量事务断言。

## 如实保留的负面结果

- 第一次上传smoke错误地在安装器尚未完成时启动，health连接被拒，exit1；因health检查最先执行，未创建测试数据。这是执行时序错误，不算产品修复。等安装器exit0/health就绪后才执行两路完整测试，均exit0。
- tar提示忽略macOS provenance扩展头，文件内容SHA与模板一致，未将警告算失败或隐藏。
- 先查错Provider表名得到relation-not-found，仅只读SQL失败；改用迁移中真实的providers表。没有读取凭据值。

## 证据与边界

证据根：`harness/evidence/SPEC-DEPLOY-001/SPEC-DEPLOY-001-20261004T004451Z-46b22950/`。deployment-summary.json记录产物、日志SHA、命令结果及负面尝试；门禁verification另列。原始日志默认不提交，SHA无法替代重跑或还原日志。总验收独立review（34e1a03..eeb385e）无P1，报告器实测与静态阅读分开；新增P2归VERIFY-002/API-PAGINATION-001/CHAT-LIFECYCLE-005，不随发布自动关闭。

未承诺：真实供应商效果/费用、复杂外部MCP协议、认证/用户、DNS重绑定、全错误排列、全量管理UI、备份实际恢复，以及已登记的输入/分页/恢复/配额等P2。132的`/hify`恰好10MiB已实测；仓库独立根路径nginx的恰好10MiB回归仍未补，不将本次替代它。

## 最终本地门禁与独立复核

最终门禁于2026-10-04T00:56:13Z结束，HEAD为3e47d13，schemaVersion=3、strictEvidence=true，result/commandResult/testCoverage均passed。backend为81类539项、migration为14类114项；两步的failures/errors/skipped/flakyAttempts均0、underfilledSuites为空，不将重复scope相加。Harness 36项及前端typecheck/build通过。backend摘要SHA为`30e05f20b691e87024866f970067da195c9937cdd5c2d71effb4e06185ded7a5`，migration摘要SHA为`35586f5b326bbce6349f8f10fa723d68ed83bdb638d0b5e2c48f8bd0aa7dab9c`，已按文件重算。

source-identity记录backend树5899367a、frontend树6181271f，源码diff为空串SHA；与构建a66be9e相同。该本地门禁与上面的132运行检查是两组证据，不互相替代。

mymacclaude只读复核eeb385e..3e47d13：无P0/P1、不阻塞发布；代码静态阅读、摘要核对、本地git/SHA核对分开，未访问132。六条P2归SPEC-DEPLOY-002：迁移后失败可能停服等人工处理（含60s健康等待）；信号中断未专门trap；previous-dist仅备份、原计划误称恢复；预检到停止之间的新Run/Workflow窗口；检索仅非空断言；超限意外202及清理失败时遗漏自身数据。当前成功发布不证明这些故障路径安全。TLS测试显式关闭证书校验不是证书指纹固定；同盘备份不是异地备份，约1.8GiB余量仍为部署风险。

后续：对方已按cf7783b提交内容复算两份摘要SHA和源码树，同意本次发布证据收口，仍未访问132。脚本本地补强独立记录在`SPEC_DEPLOYMENT_FAILURE_PATHS.md`，不回溯算作本次部署已演练故障路径。
