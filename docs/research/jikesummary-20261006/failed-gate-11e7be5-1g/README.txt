Interrupted full gate: 11e7be5, 2026-10-07

NOT a passed or completed verification. The six-scope invocation was deliberately
stopped with SIGTERM at 04:12:21 CST, exit 143, after definite failures and direct
host swap-pressure observations. Process group 88896 and its exact membership,
including Maven's worktree cwd, were checked before signalling. Session 66614
returned terminal exit 143; no process-group members remained. The exclusively
owned hify-verify-20261004 VM was stopped at 04:13; default and dify stayed running.
No other project or production service was stopped, and no test limit was raised.

Counts and boundaries:
- Harness Python: 78 tests, 2 errors, both original installer EPIPE subcases timed
  out at 10 seconds. Raw harness-python-tests.log is retained, not replaced by a
  later narrow passing run.
- PostgreSQL: six COMPLETE class XMLs report 63 tests / 1 failure / 2 errors /
  0 skipped / 0 flaky attempts. Nine expected classes lack completed XML, including
  PostgresConcurrencyIntegrationTest which was in flight at interruption.
- Backend/runtime/eval/frontend scopes never started in this invocation.
- No verification.json was produced. partial-results.json is an observation
  manifest, not a fabricated green harness manifest. reported-cases.json derives
  each case and failure from the private raw XML and retains its original SHA.
  Original XML stays in the invocation directory; environment/system properties
  were not copied into the repository. Text reports and full migration log remain.

Failures must not be collapsed into 'machine slow':
1. WorkflowKnowledgeReviewPostgresTest expected FAILED but observed SUCCEEDED.
   The same-run 03:40 stack showed Mockito bytecode generation still occurring
   inside the asynchronous fault-injection transaction. Prebuilding that mock is
   a narrow candidate correction; the real PG method must still be revalidated.
2. Both RunShutdownPostgresTest methods exceeded their original 60-second limits.
   The retained suppressed assertions also matter: COMPLETED vs TIMED_OUT during
   model recovery, and a computed-result latch that did not become true.
   The computed-result phase log shows 365.323s before first context started.
   The new createdAt-based budget intentionally includes restart time; the first
   failure still needs actual run-time evidence, not assumption or relaxed asserts.

Resource samples are short observations, not a performance benchmark or exclusive
root-cause attribution. In retained vm_stat output, page size is 16384 bytes and
the two interval swap-in deltas are 9246 and 12802 pages (144.47 and 200.03 MiB).
iostat's two interval observations are 424.71 and 174.54 MB/s. Its first line is
the longer cumulative average, not that interval. An earlier chat estimate used
decimal MB values with a MiB label; that estimate is superseded by these raw values.
The verification VM's observed PostgreSQL RSS/accounting was about 51MiB; this
does not establish that the database was the source of host-wide pressure.

All source stayed fixed at 11e7be5 until the invocation and its group had stopped.
Subsequent fixture diagnostics/corrections and narrow tests are separate evidence.
Full required verification and independent review remain outstanding.
