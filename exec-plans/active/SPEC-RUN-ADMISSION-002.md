# Run admission rejection

Isolate B2 from broader shutdown/terminal projection work. Reproduce with an actual bounded executor through the HTTP endpoint and disposable H2. A committed Run rejected by local capacity must end with a fixed EXECUTOR_REJECTED reason and replayable terminal event. Retry with the same idempotency key must return that Run without another submission/message; new key is required for fresh work.

Use one dispatch helper for create/recovery, preserve existing cancellation flags, settle rejection through the existing atomic terminal writer and clear only the rejected Run's local bookkeeping. A refusal during startup must not abort other pending Runs. No schema changes, shared services, real credentials, or deployment. Shutdown interruption is NOT solved by admission rejection and remains SPEC-RUN-ADMISSION-001.
