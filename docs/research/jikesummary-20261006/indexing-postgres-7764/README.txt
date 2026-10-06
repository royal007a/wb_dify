PostgreSQL 单方法复验（不是完整门禁）

执行时 HEAD：9d22af58210140fdf2172a49c06edc7b62583540。
被复验的 Java 夹具修正来自 7764bf2088058a52d9dab483381761ce8c876f0b。
两者之间仅研究/编译证据改动，测试期间 Java、配置和 harness 验证脚本没有修改；
只有额外的 C066 阅读笔记和离线课程片段探针在整理。

实际命令（开发 worktree/backend，创建全新私有 reports 目录）：
JAVA_HOME=<jdk-17.0.19> MAVEN_OPTS='-Xmx256m' \
JAVA_TOOL_OPTIONS='-Xmx768m -XX:ReservedCodeCacheSize=128m -Dspring.test.context.cache.maxSize=2 -Dhttp.nonProxyHosts=localhost|127.*|[::1] -Dhttps.nonProxyHosts=localhost|127.*|[::1] -DsocksNonProxyHosts=localhost|127.*|[::1]' \
DOCKER_CONTEXT=colima-hify-verify-20261004 \
DOCKER_HOST=unix://<user>/.colima/hify-verify-20261004/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
mvn -B -o -Dapi.version=1.44 -pl hify-app -am \
 '-Dtest=WorkflowKnowledgeReviewPostgresTest#indexingMetadataFailurePersistsFailureWithoutDeletingExistingChunks' \
 -Dsurefire.failIfNoSpecifiedTests=false \
 -Dhify.test.reportsDirectory=<fresh-private-dir>/reports test

真实输出目录 /tmp/hify-index-pg-7764.FZKM7C；不是复用 target/surefire-reports。
只使用自己的 hify-verify-20261004 profile（1 CPU、1GiB），不动 default/dify。
Testcontainers 启动 pgvector:pg16，生产 Flyway 24 项迁移实际执行；测试继承的
BeforeEach 还断言 select version() 为 PostgreSQL，不用 Docker 不可用跳过充当通过。

2026-10-07 04:46:21 +08:00 Maven exit 0，总耗时 08:59 min。
唯一完整 XML 类：com.hify.api.WorkflowKnowledgeReviewPostgresTest。
tests=1 / failures=0 / errors=0 / skipped=0，唯一方法与命令相同。
XML suite time=444.445s，testcase time=31.808s；Spring 启动日志=297.789s。
这些时间范围不同，不互相替代。启动慢是实测观察，不证明其他红灯只是环境问题。

仍保持的关键断言：原5秒等待；故障位于真实写事务；任务和文档FAILED；
错误正文脱敏；之前的chunks逐项不变。mock元数据提前构造，不再在异步回调中
首次生成Mockito类。它支持该夹具修正，但本次没有把修复撤掉在PG上重跑，
因此不声称这一次单方法对照已经独立证明完整旧失败的根因。

提交文件与SHA256：
maven.log a806b063ce8dc099ed98f241b15e3cb1cdaaaa6f529d3da74fb41a093ad8a32d
test-report.txt c49091394707906aa5f0372758fa2894b39304f0e5425c3e62c1b0d302e37add
原XML SHA256 02cf037281c5ad055bc4c0b3807e8d899ad15c207ad1658848e8c9dc656b1c88
原XML含进程属性/system-out，仅保留在私有目录，不提交。
日志凭据特征检查无命中，内容为合成fixture；这不是一般性的脱敏完整性证明。
日志原始空白保留，不为了git diff --check而改写原始输出。

本次不覆盖该类另外18项，不覆盖关停/恢复、全部backend或其他scope。
两轮完整门禁失败记录保持原样；CHAT-RETRIEVAL-CONTROL-001仍未验收，不部署。
