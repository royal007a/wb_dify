# Provider local-failure classification (review B1)

Task: `SPEC-PROVIDER-LOCAL-FAILURE-001`; baseline `370d223`.
Evidence directory: `harness/evidence/SPEC-PROVIDER-LOCAL-FAILURE-001/SPEC-PROVIDER-LOCAL-FAILURE-001-20261003T181315Z-126518e8/`.

## Counterexamples and fix

`red.log`: nine `CircuitBreakerServiceTest` cases, three failures, exit 1. Five user cancellations open the provider breaker; inner HTTP executor rejection also opens it; a cancellation racing with an upstream timeout records a provider failure. The original six tests pass.

Local capacity is now a typed `ExecutionRejectedException` (wire classification remains REQUEST_FAILED), without the JDK executor description/cause. A circuit-breaker permit is released without recording success or failure for local rejection or cancelled/expired Run control. Per-call control is checked, including when HTTP cancellation manifests as a normal timeout/IO failure. Queued expired work checks control before acquiring a permit. Genuine upstream timeout/unavailable errors still count after retry exhaustion. HALF_OPEN cancelled probes release their permit instead of consuming probe capacity.

## Focused verification

`mvn -B -f backend/pom.xml -pl hify-common,hify-provider -am test`, `green.log`, exit 0: common 40 + provider 23 = **63 passed, zero skipped**. Breaker tests include real timeout/unavailable failure counting, provider isolation, 20 cancelled HALF_OPEN probes, outer/inner executor refusal and sanitization. HTTP/native adapter tests use loopback fake upstreams, not paid models or real credentials.

Code commit: `03eafd6`. The atomic runner repeated the 63-test command (exit 0, zero skips) and passed the explicit Harness gate at 2026-10-03T18:22:51Z; see `verification.json`. No database/schema, browser, deployment or application-shutdown recovery validation is claimed here. The separate B2/E1 Run admission/shutdown task is not fixed by this change. This first fix incorrectly ignored model-deadline failures when cleanup interrupted HTTP; the follow-up below supersedes that classification.

## B1 follow-up: settle model SLA expiry before interrupting the worker

Task `SPEC-PROVIDER-LOCAL-FAILURE-002`, baseline `c08fbff`. Evidence: `harness/evidence/SPEC-PROVIDER-LOCAL-FAILURE-002/SPEC-PROVIDER-LOCAL-FAILURE-002-20261003T185305Z-d0bc5012/`.

`red.log` runs two new tests against the previous implementation: both fail (exit 1). A real loopback HTTP server receives five POSTs without responding; the caller times out each time but the breaker records zero failures. Separately a HALF_OPEN operation ignoring interruption retains its permit after the caller times out.

Each execution now owns a once-only probe settlement shared between caller and worker. The caller settles before cancelling the Future. An admitted, attempted operation exceeding its model deadline records TIMEOUT, even if the worker cannot stop. User cancellation, Run deadline and queue-only expiration release/avoid permits without failure or success credit. Late worker failure/success cannot settle again. Bookkeeping synchronization never encloses the operation or IO. Typed cancellation is recognized through exception causes; generic InterruptedIOException is not ignored (SocketTimeoutException is its subtype).

`green.log`: `mvn -B -f backend/pom.xml -pl hify-common,hify-provider -am test`, exit 0, **47 common + 24 provider = 71 passed, zero skipped**. Six new cases cover real stalled HTTP opening the breaker after five samples, blocked HALF_OPEN late success, Run deadline releasing HALF_OPEN capacity before worker return, queue-only model expiry, wrapped cancellation versus genuine socket timeout, and cancellation during retry wait. Existing cancellation/retry/provider isolation tests remain passing.

Boundary: attempted means entry into the supplied operation, not proof that bytes reached the server. An opaque operation's inner queue cannot be distinguished from its IO without a separate admission signal. This change does not wire streaming into a breaker, change shutdown recovery, or claim deployed verification.

Final gate on code `6775e6e`: the atomic runner repeated all **71 passing tests**, zero skipped, and explicit Harness verification passed at 2026-10-03T19:01:55Z. See command.log and verification.json. No shared services or real credentials were used.
