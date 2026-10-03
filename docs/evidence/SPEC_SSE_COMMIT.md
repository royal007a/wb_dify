# SSE committed-event verification

Task: `SPEC-SSE-COMMIT-001`; finding A07. Code commit: `a1c2015`.

## Contract

- Event sequence allocation and insertion hold the Run database row lock through commit. Sequence numbers are per Run; global event IDs can have rollback gaps.
- `saveAndFlush` is not publication. Only committed event rows are delivered. Commit callbacks drain persisted rows after each subscriber's cursor, so callback reordering and replay/subscribe interleaving cannot duplicate records on one connection.
- Local subscription locks are stable bounded stripes; completing a subscription does not delete a Run lock and create a double-lock window.
- A terminal Run row alone does not close SSE. A committed terminal event closes the connection after delivery; reconnecting after an already consumed terminal event closes without replaying it.
- Run terminal state, successful assistant message, optional Workflow terminal projection and final Run event commit atomically. A failure inserting the final event rolls back the success and assistant message. Cancellation requested before the final transaction wins over stale success.
- Database durability does not mean exactly-once client receipt. Clients must retain event IDs, deduplicate replay and reread canonical Run/message state. This remains a single-process runtime; no distributed executor claim.

## Evidence and limits

Evidence directory: `harness/evidence/SPEC-SSE-COMMIT-001/SPEC-SSE-COMMIT-001-20261003T172003Z-1b8c2194/`.

1. Before repair, four controlled SSE/transaction tests ran: two failed (pre-commit event leakage and premature closure on terminal row). `unit-red.log` preserves the counterexamples.
2. Initial repaired broker four tests plus Run workflow control five tests passed: `unit-green-initial.log`. Two additional cases now cover consumed terminal cursor and rejecting nontransactional publication; the authoritative total will be in the Harness command gate.
3. First independent PostgreSQL command failed before test bodies: six context errors caused by `UnknownHostException: 127.0.0.1` in JDBC under local SOCKS proxy configuration. `postgres-proxy-failure.log` is retained; it is not a product assertion failure or a passing verification.
4. The identical database/test code with the Harness loopback proxy bypass passed all seven integration cases, zero skips: PostgreSQL concurrency six (including rollback-not-visible, six concurrent event publishers and terminal-event failure rollback), plus HTTP/H2 Workflow cancellation one. `postgres-direct-green.log`.

PostgreSQL command environment: Colima Docker socket, Docker API 1.44, Testcontainers host 127.0.0.1; `JAVA_TOOL_OPTIONS` sets http/https/socks non-proxy hosts to localhost, 127.* and [::1]. Only disposable test databases were used. No shared business data or running deployments were modified.

The atomic task's full RunFlow command and migration scope remain the final gate; this evidence does not claim production redeployment, full system regression, or real-provider streaming validation.
