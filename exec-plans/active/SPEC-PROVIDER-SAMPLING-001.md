# Provider sampling after B1 rereview

6775e6e fixes a truly hanging upstream when its model SLA expires before Run budget, with once-only settlement. Reviewer confirmed by static reading (did not rerun tests). Run budget expiry is deliberately treated as local cancellation of the sample, so later turns/short configured Runs may mask repeated stalls. Conversely operation entry precedes the inner LlmHttpClient executor, so model expiry may attribute local queue delay to a provider.

Do not solve one by regressing the other. Introduce explicit per-attempt transport/admission timing only after real loopback queue/stall counterexamples. Establish policy for censored Run budget samples, including minimum observation time and global resource exhaustion. Test both directions with the same matrix. A startup model-timeout < run-timeout check alone cannot fix later turns' shrinking budget. Streaming currently bypasses this breaker and needs its own pre-first-token/no-retry-after-token contract.

Add controlled caller/worker deadline versus 503 interleavings, retry-backoff expiry, caller interruption and HALF_OPEN continuation. Improve short-timeout tests with worker-entry synchronization without hiding lateness. No paid models, shared services or actual credentials.
