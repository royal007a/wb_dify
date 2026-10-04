# Harness 完成证据准入

范围：SPEC-AUDIT-004，基线ebee870。工程状态机，不新增产品HTTP接口。`passed`只表示选定scope的命令/计数，不等于全功能、部署或真实模型效果验收。

## 完成与回读

1. finish只能在当前running任务、当前runId和HEAD、要求的scope/步骤、schema v3、strictEvidence、passed、exit 0及零skip/失败/flake时完成。先验证现有状态，再核验本次证据；无跳过检查的命令行开关。
2. 每一步真实日志须在本run证据目录内，按字节复算SHA256；Maven摘要须存在、SHA一致，schema 1、step和invocationId匹配，result=passed、commandExitCode=0，tests对象与verification内嵌值完全一致。目录越界和符号链接逃逸拒绝，不能用其他目录的相同字节充当本次文件。
3. 基线已有86条历史记录按taskId、顺序、runId、规范JSON SHA256固定于`harness/legacy-evidence.json`。规范化仅对象键排序、紧凑分隔符、UTF-8、不转义Unicode；不重新判定历史测试。修改/移除/重排前缀不合法，新completed不能通过省略schema、降级或伪造新历史获得豁免。
4. validate逐条检查completed证据，不只看最后一条，也不因任务当前不是completed而跳过已有完成记录。任务completed要求非空、completed尾记录。每条新完成记录需要v3、exit 0以及已记录verification SHA。
5. 离线git archive不携带原始日志/XML：validate仍要求新Maven摘要及verification可读、匹配，原始日志存在时也必须匹配；缺少日志仅表示未复算日志。finish始终要求实际日志，不能拿便携校验代替运行验收。历史v3继续校验manifest及record摘要；原历史缺失日志不伪装已复算。

## 具名反例与正向对照

均位于`harness/tests/test_harness.py`，临时目录、合成日志/摘要；不运行Maven、Docker、业务库或部署。

| 测试 | 必须证明 |
|---|---|
| test_finish_requires_actual_log_bytes_and_current_directory | 日志修改/删除/越界/符号链接逃逸拒绝；恢复合法文件能完成 |
| test_finish_reads_summary_result_identity_counts_and_digest | 缺摘要、摘要SHA变化、partial、错误invocation/step/计数/exit拒绝；合法摘要完成 |
| test_completed_cannot_clear_evidence_lower_schema_or_append_fake_legacy | 清空、降schema、删schema、追加假旧记录、blocked尾掩盖均拒绝；原记录通过 |
| test_legacy_compatibility_is_an_exact_frozen_prefix_not_a_schema_switch | 精确历史前缀兼容；改run/path、删历史、追加假completed拒绝 |
| test_all_completed_records_are_checked_not_only_the_last | 第一次与第二次均合法完成后，篡改第一次manifest也能检出 |
| test_portable_validation_does_not_authorize_finish_without_logs | 缺日志不能finish；已合法完成后仅删ignored日志可便携校验，坏日志/缺摘要仍失败 |
| test_summary_mutation_after_finish_is_detected_without_raw_logs | 已完成后摘要改partial，缺原始日志也不妨碍检出 |
| test_missing_inventory_or_malformed_evidence_fails_closed | 清单缺失、坏记录类型或不完整新completed拒绝 |
| test_summary_and_verification_symlinks_cannot_escape | 完全相同字节的外部摘要/manifest也不能经符号链接冒充本run文件 |
| test_run_task_cannot_complete_with_a_passed_manifest_but_missing_log | 真实runner+假verify声称passed却删日志：finish退出2，runner收敛blocked而非completed |

既有生命周期、权限、progress、partial和runner合法完成用例继续保留。测试存在与本轮通过须分别报告，结果见专门evidence。该机制不防同时修改仓库/兼容清单/摘要/日志的恶意写者，不是数字签名。未重新解析提交时不存在的原始XML，也不以合成测试推断真实Maven运行成功。
