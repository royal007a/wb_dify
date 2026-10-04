# SEMANTIC-001

User goal: real semantic retrieval, Workflow nodes, verify and redeploy within the two-hour work period. Task state lives only in harness/tasks.json.

1. Add a provider public embedding port, using existing credential policy, HTTP timeouts/cancellation and circuit breaker. OpenAI-compatible embeddings; no secret values in configuration/evidence.
2. Add per-KB immutable embedding profile (provider identity/config reference, model, dimension). Persist real vectors beside existing legacy hash vectors. Legacy profiles remain unchanged; no silent semantic-to-hash fallback.
3. Query vectors use each persisted profile. Exact cosine ranking for semantic vectors initially (not claimed as ANN/HNSW acceleration); RRF with lexical results. Include profile/vector in new corpus manifests while preserving old manifests.
4. UI configuration, protocol failure tests, H2 and PostgreSQL integration, real model synonym evidence. Keep deployment separate.

Risks: provider-side model weights can change under the same model name; credentials can rotate. No claim of bitwise reproducibility across upstream model changes. New columns are additive; rollback binary does not remove them.
