# Single-instance create/recovery dispatch ownership

Task `SPEC-RUN-DISPATCH-001`, baseline `7bb2e3f`.
Evidence: `harness/evidence/SPEC-RUN-DISPATCH-001/SPEC-RUN-DISPATCH-001-20261003T191810Z-fac85812/`.

## Counterexamples

`red-initial.log` had two assertion failures and one fixture lock-timeout error: a latch inside the spy publish method unintentionally held its transaction. That is not evidence for the third product case. The corrected fixture waits from afterCommit, after the row update is committed.

`red.log` then shows **three assertion failures, zero errors/skips**, exit 1: recovery attempts a second submission while create's first is live; recovery completes the row before delayed create dispatches it a second time; cancellation returning after owner cleanup creates an orphan true flag. All interleavings use latches, Spring transactions, disposable H2 and actual application services. No shared deployment is involved.

## Change and boundary

Per-Run dispatch ownership is separate from the cancellation flag. One owner covers claim, queued submission, execution and cleanup. Reentry returns without submitting, failing or deleting active state. Release compares owner identity and removes control maps before making the slot claimable. A fresh row read skips already terminal work; first execution checks persisted cancellation before Provider/Knowledge/Model work. Cancel only updates a present flag; absent flags rely on cancelRequestedAt instead of leaking a new map entry. Create rereads state after dispatch, including when recovery already finished it.

This is a **single-instance** guarantee. No durable lease, multi-instance fencing or automatic external side-effect rollback is claimed. OPERATIONS prohibits overlapping application instances against the same Run database until that separate contract exists. Rejection during shutdown and database failures in recovery remain SPEC-RUN-ADMISSION-001. Original B2 static review accepted the rejection fix but identified these ownership issues; it did not execute tests.

## Verification

`green-final.log`: `mvn -B -f backend/pom.xml -pl hify-app -am -Dtest=RunAdmissionIntegrationTest,RunDispatchIntegrationTest,RunWorkflowControlTest,RunFlowIntegrationTest,WorkflowRunControlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`, exit 0, **16 pass, zero skipped**. Four dispatch interleavings (the three red cases plus persistent cancellation before any dispatch/model/tool event), three admission cases, six application workflow-control cases, two HTTP RunFlow cases and one HTTP Workflow cancellation case. All three local maps, including ownership, are checked empty after completion/refusal. No PostgreSQL/browser/deployment claim in this slice.
