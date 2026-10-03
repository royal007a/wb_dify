# E1 review follow-up: computed outcomes and production destruction

Task: SPEC-RUN-SHUTDOWN-002. Code baseline 2f3e219 (E1 code 5e37fff); registered task baseline 1a4b418.
Evidence: `harness/evidence/SPEC-RUN-SHUTDOWN-002/SPEC-RUN-SHUTDOWN-002-20261003T200338Z-6fd9d532/`.

## Verified counterexamples

- `red-review.log`: RunWorkflowControlTest, 12 tests / 3 failures / 0 errors or skips. Workflow returned SUCCEEDED, TIMED_OUT or FAILED, then stopping began; all incorrectly left the parent RUNNING. App tests did not run because Maven stopped at this module.
- `red-real-close.log`: 5 actual-context tests / 1 assertion failure / 0 errors or skips. A real QueryLoop computed a final result and emitted delta; a latch held its return until the actual ContextClosedEvent. Parent stayed RUNNING instead of COMPLETED. The close listener only released the computed result and waited for the worker; it did not invoke or change pool.shutdown.
- `red-loop.log`: WorkflowEngineTest, 4 tests / 1 failure. END computed SUCCEEDED, then stopping changed it to INTERRUPTED. QueryLoop tests were not reached in that failed Maven run.

## Fix and strengthened shutdown assertions

Stopping closes admission, not settlement of already-computed outcomes. Run terminal suppression now requires an explicit APPLICATION_SHUTDOWN result. Typed suspension/interruption exceptions retain a recoverable RUNNING record; ordinary computed outcomes commit under the same row lock and persisted cancellation still wins. Workflow INTERRUPTED propagates suspension explicitly, while returned completed/timeout/failure outcomes are not discarded merely because the process is stopping.

QueryLoop permits a complete final model response to go through the existing cancellation, deadline and FinishGate checks during shutdown. Tool-bearing responses cannot start new tools. Workflow no longer rewrites an already-computed END result to INTERRUPTED at its final save. Existing cancel/deadline semantics and the separate Workflow/parent late-cancellation projection issue remain outside this fix.

The model-blocking shutdown fixture no longer has a listener that manually calls pool.shutdown or changes its wait configuration. It uses the production destruction path and asserts **run.interrupted exists before restart**. The user-cancel fixture asserts **terminal_reason=CANCELLED before restart**, so CANCELLED_DURING_RESTART cannot hide a failed shutdown write. The separate completed-outcome fixture uses a latch/listener to place shutdown precisely between calculation and commit; it is not evidence of executor destruction order.

## Verification before final gate

- `green-review.log`: 45 passed, 0 skipped (4 common, 4 Workflow, 32 Chat/application unit, 5 context tests).
- `green-pg-regression.log`: 70 passed, 0 skipped (22 common, 12 Workflow, 20 QueryLoop, 16 app). Includes two real PostgreSQL context-close/restart cases, six PostgreSQL transaction/concurrency cases, dispatch/admission and HTTP Workflow cancellation. All containers/ports/databases are test-owned; not shared services.
- `green-computed-clarify.log`: 6 context tests passed, 0 skipped. Added a real calculator-missing-input loop reaching NEEDS_INPUT before shutdown; the structured clarification is committed once, no assistant fabricated, and restart does not re-run the model. COMPLETED similarly retains one assistant and one terminal event.

The runner's final explicit Harness + migration gates, commit IDs and mutation results are recorded below after they execute. Commands use `mvn -f backend/pom.xml -pl hify-app -am -Dtest=<listed classes> -Dsurefire.failIfNoSpecifiedTests=false test`; PostgreSQL uses the existing Colima/Testcontainers environment and `-Dapi.version=1.44`.

## Boundaries and review P2s

This does not fix the Chat budget reset across restart: QueryLoop still receives a fresh runTimeout, unlike Workflow's createdAt-based remaining budget. The spec/operations text now states the difference. WorkflowRecovery bulk-update failure, not only initial Run-list failure, can prevent startup. Child-task recovery listener ordering remains unverified and is not claimed safe. Database unavailability, interrupted JDBC/connection acquisition, non-cooperative drivers, hard kills, time rollback and external side-effect replay retain the existing limits. No browser, paid Provider or deployment claim; 132 and hify-cc were untouched.
