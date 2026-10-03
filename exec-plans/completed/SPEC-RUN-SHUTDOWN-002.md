# E1 review: completed outcomes survive shutdown

Scope: two P1 review items at 5e37fff; only ~/hify, no shared services or deployments.

1. Reproduce completion → stopping → final commit with deterministic latches and a real database.
2. Remove the blanket terminal suppression: explicit suspended work remains recoverable; computed outcomes may commit during shutdown. User cancellation under the row lock still wins.
3. Use actual context.close with unmodified production executor destruction (no test-triggered pool.shutdown). Assert interruption events before restart and exact CANCELLED reason.
4. Test Workflow completed outcomes, regression, and ephemeral PostgreSQL. Correct documented budget/recovery limitations without claiming the separate deadline/child-recovery issues fixed.

No schema or route changes. No external side-effect replay guarantees. Rollback is a new commit, never reset shared work. Full product verification/deployment remains separate.
