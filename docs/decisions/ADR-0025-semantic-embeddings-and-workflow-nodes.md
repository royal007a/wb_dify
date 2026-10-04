# ADR-0025 — Semantic embeddings and bounded Workflow IO

2026-10-04: user explicitly requested real semantic retrieval, additional Workflow nodes, verification and redeployment. This extends Phase 4/5 beyond bootstrap-only implementation; immutable publication, credential references and the prohibition on unconfirmed write effects remain mandatory.

New KBs can select `embedding: {providerId, model, dimensions}`. Server freezes Provider type/base URL/auth reference and model/dimension. Profiles cannot change in place: create another KB and publish new versions. Legacy KBs retain token-hash retrieval, explicitly not semantic embedding. Real vectors are separate, dimension/finite/nonzero/count/index checked; failures never fall back to hash.

Document embedding is batched (32), outside the write transaction. Query embedding occurs before the REPEATABLE_READ validation/ranking transaction; semantic searches originating inside an existing transaction are rejected. Initial semantic ranking is exact cosine in application memory plus lexical RRF, **not ANN/HNSW-accelerated**, with linear corpus cost. Legacy hash indexes remain. New profile/vector bytes join the frozen manifest; legacy manifests remain identical.

Upstream weights may change under one model name. Frozen metadata/digests detect stored-vector tampering but do not pin remote weights or certify answer semantics.

LLM and API_CALL nodes use bounded IO and shared cancellation. Published graphs own their provider snapshot. Until a side-effect ledger and exact confirmation exist, HTTP nodes are GET-only with explicit operator endpoint grants; POST/write methods are rejected.

Fixture-provider protocol/PG tests and real-model evidence are separate. Gates require zero skip; deployment evidence binds code/artifacts/migration and synthetic online smoke. No quality claim follows from passing fixture vectors.
