# SSE slow-subscriber isolation and cursor ownership (F)

Task `SPEC-SSE-BACKPRESSURE-001`, baseline `0fd14e6`.
Evidence directory: `harness/evidence/SPEC-SSE-BACKPRESSURE-001/SPEC-SSE-BACKPRESSURE-001-20261003T183234Z-1e6b28a8/`.

## Red and implementation

Old broker: `RunEventBrokerBackpressureTest` two run, one assertion failure and one timeout error, exit 1 (`red.log`). A blocked sender holds the afterCommit callback; an unknown/foreign cursor is accepted. The controlled sender uses a latch and releases it in finally, not a timed sleep hiding completion.

The replacement has one sender owner per subscription, at most 64 (configurable 1–256), no pending subscriber task queue, and coalesced wakeups rather than one queued item per event. Database history is read in pages of 32. No database calls or network writes occur on the afterCommit/heartbeat thread, and repository reads finish before the sender writes. There are no shared stripe locks. A small initial heartbeat is buffered by Spring; history is not read until MVC commits that handshake, so history cannot fill Spring's pre-initialization buffer.

Admission fails fast with JSON HTTP 503 / 50300. Each subscriber advances its own cursor only after send; terminal delivery closes that projection. A rejected/slow/disconnected stream does not cancel its Run. Reconnection uses committed history, not an in-memory queue. Missing/0 cursor means beginning; positive cursor must belong to this Run, otherwise JSON HTTP 400 / 40000.

Tomcat NIO's finite connection timeout also supplies its blocking socket write timeout (verified against local Tomcat 10.1.40 `NioEndpoint.setSocketOptions` / `NioSocketWrapper.doWrite`). Default configuration is 10s, configurable with HIFY_HTTP_CONNECTION_TIMEOUT. This is an inactivity timeout, not a strict total-transfer deadline for a continuously slow reader; subscription count remains bounded. Reverse proxies must enforce their own limits. No authentication/tenant connection quotas are claimed.

## Verification before final gate

- `green-unit.log`: broker 6 + backpressure 3 = **9 pass**, zero skips. Includes old controlled races, colliding run IDs, heartbeat returning while a sender is blocked, bounded admission, and no history fetch before handshake.
- `network.log`: actual embedded Tomcat on a random port with disposable H2, **3 network tests**; RunFlow 2 + Workflow cancellation 1 also pass (**6 total**). The socket test preloads 512 x 16KB events, sets a small TCP receive buffer, never reads its socket, and observes an actual `NioSocketWrapper.doWrite` stack on the SSE worker. While blocked, Hikari active connections reach zero, another publish commits in 3ms, and another Run's terminal stream completes. The 1s test write timeout reclaims the slow subscription; reconnect after a known cursor returns exactly the remaining three deltas plus terminal. No shared service is used.
- Actual HTTP negative tests cover foreign, unknown, negative and zero cursors and saturated subscription 503 with no JDK executor detail. Closing/reclaiming subscriptions is awaited.
- `browser.log`: dedicated Vite port 5187, **8 Playwright lifecycle tests pass** (11.3s). HTTP/EventSource are controlled in this suite: it verifies UI duplicate/reconnect/terminal reconciliation behavior, not a real-provider or deployed network E2E. Actual network behavior is tested separately above.
- PostgreSQL's concurrent publisher test now keeps a live subscriber, concurrently commits six events, then verifies the received seven IDs (including terminal) exactly match committed order, without duplicates. Final migration gate records this result; the original mocked callback tests alone are not evidence of multi-threaded database concurrency.

Final atomic command repeats the 9 broker tests + 6 HTTP/network tests; explicit Harness/migration gate is recorded in verification.json. No database migration is added. Deployment, Workflow/Run terminal projection races, B1 model-deadline classification and wider system regression remain separate work.
