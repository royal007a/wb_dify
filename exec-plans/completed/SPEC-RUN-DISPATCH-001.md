# Single-instance dispatch ownership

B2 rereview identified create/recovery overlap before ApplicationReadyEvent as a real single-instance duplicate execution path. First reproduce by stopping the initial executor submission while recovery scans the committed RUNNING row; arrange the second submission to reject. Verify the active Run is not failed by the duplicate and the operation executes only once.

Use an explicit per-Run dispatch owner, distinct from a cancellation flag. Acquire before executor submission, check current persisted state before execution, retain through worker completion, and compare owner on release. A late cancellation must not recreate an orphan flag after cleanup. Persistent cancelRequestedAt is authoritative before the first outbound action, not just a memory flag.

Hify currently promises single-instance execution. This local guard is NOT a database lease and must not be described as cross-instance fencing or safe rolling overlap. Multi-instance ownership requires durable attempt/lease CAS and remains a separate extension, not an implied guarantee. Shutdown rejection/deferred recovery and DB errors during recovery remain SPEC-RUN-ADMISSION-001; do not silently hide those failures.

Use isolated HTTP/H2 and controlled latches. No shared services, real credentials or deployment. Retain old red evidence and existing rejection/idempotency/SSE tests.
