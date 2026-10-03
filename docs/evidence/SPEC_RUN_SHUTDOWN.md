# E1 shutdown is not cancellation

Task `SPEC-RUN-SHUTDOWN-001`; baseline `22e9d77`.
Evidence directory: `harness/evidence/SPEC-RUN-SHUTDOWN-001/SPEC-RUN-SHUTDOWN-001-20261003T193447Z-c75a236c/`.

## Counterexample and fixture limits

The first uncoordinated context-close test (`red-model.log`) **passed on the old code**, because the persistence layer closed before its CANCELLED write; the worker failed writing its terminal. This is not proof of correct cancellation semantics. The direct compiler:testCompile experiment (`red-compiled-baseline.log`) failed dependency resolution, not a behavior assertion, and is not counted.

An isolated `git archive 22e9d77` at `/tmp/hify-e1-baseline-CUUadD` received only the test fixture retained in `docs/evidence/fixtures/RunShutdownBaselineTest.java.txt` (as `backend/hify-app/src/test/java/com/hify/api/RunShutdownIntegrationTest.java`). The fixture uses actual context.close, with a shutdown listener making the interrupted executor finish while persistence is still available. `mvn -f backend/pom.xml -pl hify-app -am -Dtest=RunShutdownIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` then fails **1 assertion, 0 errors/skips**, exit 1: expected RUNNING, actual CANCELLED. `red-isolated-baseline.log` records this reproducible old-code failure. No shared worktree was reset.

## Implementation

- Context-scoped ExecutionLifecycle marks stopping before executor shutdown, ignores child-context close, and is fresh after restart. ExecutionControl preserves the original deadline, differentiates suspension from interruption/user cancellation, and gives explicit cancellation precedence. QueryLoop and Workflow receive this signal; tool lease validation stops new execution. Provider probes ignore shutdown samples and release HALF_OPEN permission.
- Terminal commit checks stopping under the Run row lock: without persisted cancellation, leave RUNNING, append non-terminal run.interrupted when DB is available, and do not save assistant/ack children. Shutdown rejection is deferred, not EXECUTOR_REJECTED capacity failure. Ordinary capacity refusal retains B2 behavior.
- Run executor interrupts and waits at most 5 seconds before EntityManagerFactory destruction (explicit dependency), instead of relying on accidental failed terminal writes. Model shutdown tests cover a forced valid shutdown ordering; Workflow and explicit-cancel cases use the unmodified production close order.
- Workflow Run/Node INTERRUPTED is distinct from CANCELLED/TIMED_OUT. Additive V22 widens two CHECK constraints only. On startup, old RUNNING records before this context's creation cutoff are interrupted before AgentRun recovery; newer requests are not touched. A recovered parent creates a new Workflow attempt of the same immutable version, not an arbitrary node replay.
- Recovery handles each row's dispatch/settlement DB exception independently; logs only runId and exception type. Initial list-read failure still can prevent startup; no periodic rescan is claimed.

## Verification

`green-contexts-v2.log`: 4 common control/breaker tests + 8 application tests + 4 real context/restart tests + 1 H2 migration test passed. The preceding contexts attempt had a test-only Mockito restubbing deadlock (`when` invoked the existing blocking stub); it was stopped and fixed with `doReturn`, plus class timeout. This is not a production failure or a successful test run.

`green-regression.log` caught one mock-fixture incompatibility: mocked ExecutionControl.withShutdown returned null; the deterministic virtual-deadline fixture now returns its controlled instance. Assertions were not removed or weakened.

`green-regression-v2.log`: **110 passed, 0 skipped**, exit 0 — common 22, Workflow 33, Chat 28, application 27. Includes real PostgreSQL terminal migration and six PostgreSQL concurrency/transaction/stream-replay tests; H2 close/restart, original admission/duplicate dispatch, HTTP cancellation, 19 QueryLoop tests, graph/engine and breaker regression. The two DB-recovery fault points are mocked failures, not a live database outage experiment.

`green-pg-shutdown.log`: **1 passed, 0 skipped**, exit 0. Real PostgreSQL Testcontainer, two actual Spring contexts, same database and Run; interruption while persistence remains writable leaves RUNNING; restart restores checkpoint and commits one assistant reply. New migration and startup orphan queries execute against PostgreSQL, not only H2.

Commands use ephemeral databases, test-owned ports and fake model/knowledge workers. No paid Provider, real MCP, browser or deployment verification is claimed here. No 132/hify-cc/shared services or secrets were touched. Explicit Harness and migration gate results are recorded after the code commit in verification.json.

## Remaining boundaries

Single instance, old process fully stopped before new startup. Neither dispatch owner nor lifecycle flag is a distributed lease. No arbitrary write-tool replay, external rollback or exactly-once side effect claim. A hard kill is represented by orphan-row fixtures, not an actual kill -9 test. Non-cooperative drivers can outlive the 5-second wait; DB unavailability can prevent interruption evidence. Run budget may expire before recovery succeeds. Workflow/AgentRun final-commit cancellation races are a separate pending slice; deployment and full product verification remain pending.
