# CAPABILITY-VERIFY-001

目标：发布前以当前backend/frontend/deploy树取得完整证据，不以窄测和历史模型smoke代替本轮。

1. 扩展既有隔离live脚本的明确任务白名单，仅增加本task；不增加任意运行入口。分别使用28082/28083、H2内存库和已有本机Ollama，合成凭据只指向loopback。每个进程结束finally关闭。
2. 原子runner的command依次包装jar、真实bge-m3三条合成改写检索、GET夹具到真实qwen串联、路由打桩浏览器测试。额外产品质量/大库吞吐/132验收均不外推。
3. verifyScopes重跑harness/migration/backend/runtime/eval/frontend，使用专用colima-hify-verify-20261004，不切换默认context。保存源码树与空diff、每步摘要和脱敏可复算方法报告，失败保留原run。
4. 完整通过及安装器独立复核后才交CAPABILITY-DEPLOY-001执行132发布。此task不连132、不碰共享数据库或真实密钥。
