# Provider local-failure classification (review B1)

Task: `SPEC-PROVIDER-LOCAL-FAILURE-001`; baseline `370d223`.
Evidence directory: `harness/evidence/SPEC-PROVIDER-LOCAL-FAILURE-001/SPEC-PROVIDER-LOCAL-FAILURE-001-20261003T181315Z-126518e8/`.

## Counterexamples and fix

`red.log`: nine `CircuitBreakerServiceTest` cases, three failures, exit 1. Five user cancellations open the provider breaker; inner HTTP executor rejection also opens it; a cancellation racing with an upstream timeout records a provider failure. The original six tests pass.

Local capacity is now a typed `ExecutionRejectedException` (wire classification remains REQUEST_FAILED), without the JDK executor description/cause. A circuit-breaker permit is released without recording success or failure for local rejection or cancelled/expired Run control. Per-call control is checked, including when HTTP cancellation manifests as a normal timeout/IO failure. Queued expired work checks control before acquiring a permit. Genuine upstream timeout/unavailable errors still count after retry exhaustion. HALF_OPEN cancelled probes release their permit instead of consuming probe capacity.

## Focused verification

`mvn -B -f backend/pom.xml -pl hify-common,hify-provider -am test`, `green.log`, exit 0: common 40 + provider 23 = **63 passed, zero skipped**. Breaker tests include real timeout/unavailable failure counting, provider isolation, 20 cancelled HALF_OPEN probes, outer/inner executor refusal and sanitization. HTTP/native adapter tests use loopback fake upstreams, not paid models or real credentials.

Code commit: `03eafd6`. The atomic runner repeated the 63-test command (exit 0, zero skips) and passed the explicit Harness gate at 2026-10-03T18:22:51Z; see `verification.json`. No database/schema, browser, deployment or application-shutdown recovery validation is claimed here. The separate B2/E1 Run admission/shutdown task is not fixed by this change. An overall model-attempt timeout still reaches the caller as TIMEOUT; if cleanup interrupts an outstanding worker, that interruption is not used as evidence of a supplier outage.
