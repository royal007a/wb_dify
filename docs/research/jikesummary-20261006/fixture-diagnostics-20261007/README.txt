Fixture corrections and diagnostics, not completed product acceptance

Motivation is the preserved 11e7be5 red gate, not a wish to loosen thresholds.

Implemented test-only changes:
1. Prebuild DatabaseMetaData Mockito fault before asynchronous indexing dispatch.
   Production metadata call still throws inside the bound write transaction. The
   transaction assertion, original five-second wait, FAILED task/document, safe
   error and unchanged chunks assertions are retained. No production code changed.
2. Installer EPIPE test uses a bounded timeout diagnostic wrapper. It rethrows the
   identical TimeoutExpired, keeps timeout=10 and all cleanup assertions, and only
   appends a synthetic service-state/allowlisted-command-stage note. No argument
   values/environment variables are included; diagnostics failure cannot replace
   the primary exception. Python >=3.11 supplies add_note; on older Python the
   original exception is preserved without a note, not silently counted as tested.
3. Model-shutdown recovery records static stage names through the existing phase
   logger. Original 60-second annotation and model-entry/interruption/recovery
   assertions are unchanged. This improves localization, not recovery behavior.

Actual repository verification:
  cd harness/tests
  python3 -m unittest -v test_fixture_diagnostics \
    test_deploy_installer.DeployInstallerTest.test_broken_stderr_pipe_preserves_cleanup_and_original_exit
7 tests passed in 14.128s. The seventh method runs sh/dash x late/health subcases
against original synthetic commands; there is no real installer/service/network.
python-narrow.log SHA256 e95ee30e4f4647803ee1acb2b98ee5bd91de453435c07597cf82dd2c50448736.

Mutation: in a separate Python process replace only run_with_fixture_diagnostics
with direct subprocess.run after dropping fixture_root; run the test that checks
the original exception identity, ten-second value, and note presence. It produces
exactly one assertion failure, zero errors, on 'timeout needs fixture diagnostics'.
The wrapper exits zero only because expected red was observed; this is not a
passing mutated test. Tracked code was not modified by the in-memory mutation.
diagnostics-mutation.log SHA256 c7b70a11fd23069e39da8e64e675fb767a2758722210d44579ac6b3f32a34f9c.

The two same-run raw JDK snapshots are retained:
- thread-0340.txt SHA256 7b8df681290d498126c5901ee600e8e80811448beabd1e943f0dbeaf32668cd4:
  async-2 still creating the metadata mock inside the indexing transaction.
- thread-0345.txt SHA256 a41ffb44681cab6a76a76675d00c3c8413f280bdfd7641c09809e891d0162fdc:
  main still in first Spring context startup for model-shutdown recovery.
Neither snapshot measures the whole interval or proves every historical cause.

Outstanding: Java compilation, original real-PostgreSQL targeted tests, all six
scopes, and independent review. The Python replay cannot fix or override the
retained PG failures. No production files, services or deployed artifacts changed.
Raw diagnostic files retain original whitespace; whitespace warnings on those
files are not corrected by rewriting failure evidence.
