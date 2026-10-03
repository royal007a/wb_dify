# B1 model deadline follow-up

Reviewer static counterexample: default model-timeout 45s is shorter than HTTP 60s/65s. Its Future.cancel interrupts a truly stalled upstream, so current code releases instead of recording a TIMEOUT. First reproduce with a local HTTP server accepting without responding, never paid models.

Separate caller stop causes: user cancellation / Run deadline / queue capacity are local; expiration of an in-flight Provider attempt is an upstream SLA timeout. Record once, restore HALF_OPEN permits appropriately; do not count queue-only time as Provider failure. Exercise races, wrapped cancellation and retry waiting where relevant, preserve fixed sanitized errors.

Run all common/provider tests and a loopback stall failure matrix, record old failing and new passing evidence. No shared services/credentials/deployment in this atomic task. Application shutdown Run semantics remain a separate task.
