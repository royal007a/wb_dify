# Run executor admission rejection (B2)

Task `SPEC-RUN-ADMISSION-002`; baseline `7af010e`.
Evidence directory: `harness/evidence/SPEC-RUN-ADMISSION-002/SPEC-RUN-ADMISSION-002-20261003T190736Z-c7c03d28/`.

## Red counterexamples

`red.log`: three `RunAdmissionIntegrationTest` cases all fail, zero errors/skips, exit 1. The test uses real Spring HTTP routing/Advice plus disposable H2. The injected Executor delegates to a real single-worker, zero-queue ThreadPoolExecutor already occupied behind a latch, so the refusal is a real AbortPolicy exception, not a fabricated error string. Creating a Run returns 500 despite its committed RUNNING row; restart convergence throws and aborts the remaining rows; a cancellation racing with admission still returns 500.

## Change

Create and startup recovery share `dispatch`. It preserves an already-set local cancellation flag (`putIfAbsent`). Rejection settles the durable Run via the existing locked terminal transaction as FAILED / EXECUTOR_REJECTED, fixed output text, no assistant message, and replayable run.failed event. Persisted cancellation still wins under the terminal row lock. Local bookkeeping is cleaned even if terminal persistence fails; a database failure is not swallowed or claimed successfully settled.

The first HTTP creation remains 202 but its body is already terminal if dispatch was refused. Same-key replay is 200 for that same failed Run, no second submission/user message. A new key creates fresh work. Recovery handles a refused row without aborting subsequent rows and preserves prior turns/tool-call counters. This does not fix shutdown interruption, distributed leases, or Workflow/AgentRun terminal alignment; those remain SPEC-RUN-ADMISSION-001.

## Verification

`green-final.log`: `mvn -B -f backend/pom.xml -pl hify-app -am -Dtest=RunAdmissionIntegrationTest,RunWorkflowControlTest,RunFlowIntegrationTest,WorkflowRunControlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`, **12 pass, zero skips**, exit 0. Admission three + existing/new mocked workflow-control six + HTTP RunFlow two + HTTP Workflow cancellation one.

The admission suite checks database state, exact safe reason, single terminal event, SSE replay through async MVC dispatch, same-key replay/no second executor submission, new-key successful work, rejected recovery alongside successful recovery, persisted cancellation racing rejection, and empty per-Run local maps. A new application-level case ensures a preexisting cancellation flag is not reset on dispatch. It is distinct from the real DB/HTTP cases. No PostgreSQL, browser, paid model, shared deployment or real-credential verification is claimed in this atomic slice. No schema migration.
