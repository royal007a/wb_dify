# Advanced platform slices release — 2026-09-21

## Release identity

- Deployed code commit: `d39427f`
- Remote rollback baseline: `7bb7031`
- Branch: `main`
- Public URL: `https://118.196.123.132/hify/`
- Local URL: `http://localhost:8088/`
- Database schema: Flyway `V17` (`mcp server registry`)

## Delivered slices

1. Knowledge/RAG: TXT/Markdown ingestion, asynchronous indexing, recursive chunks,
   PostgreSQL FTS + pgvector candidate retrieval, weighted RRF and canonical citations.
2. Workflow: validated draft graph, immutable published version, deterministic condition
   branches, Knowledge node, variable context and persisted node trace.
3. MCP: Streamable HTTP protocol subset, versioned `tools/list` snapshots, schema digest,
   credential references, endpoint guard and READ-only debug `tools/call`.
4. Console: real Knowledge, Workflow and MCP management pages, while retaining the
   existing Provider, Agent and streamed Chat paths.

## Verification evidence

- Harness matrix `backend,frontend,migration,runtime,eval`: passed.
- PostgreSQL migration test: passed through V17 with no migration skipped.
- Local runtime: health HTTP 200; Flyway V17; Playwright Chat + management smoke 2/2.
- Remote runtime: service active; health HTTP 200; Flyway V17; Knowledge, Workflow and
  MCP list APIs HTTP 200; public HTML loaded; Playwright Chat + management smoke 2/2.
- Evaluation baselines remained green: history hybrid Top1 `1.0000`; intent dataset
  Top1 `0.8250`, unknown recall `1.0000`.

## Deployment note

The remote Docker build could not resolve Docker Hub during the rollout. The release was
completed through the host-native, pre-existing systemd/nginx topology instead: Maven had
already produced the new Java artifact, the Vue bundle was rebuilt with `/hify/` base and
API path, then `hify.service` was restarted. This preserved the existing PostgreSQL volume
and public TLS route. Docker registry availability is therefore not part of the runtime
dependency for this release.

## Explicit remaining boundaries

- Knowledge, Workflow and MCP are independent vertical slices; they are not yet bound into
  AgentVersion/Chat runtime selection.
- Knowledge embedding is a deterministic bootstrap implementation, not a production model.
- MCP client covers the tested Streamable HTTP request path; official Java SDK conformance,
  full SSE/MRTR compatibility and DNS-rebinding-resistant address pinning remain follow-up.
- Workflow currently has deterministic nodes only; LLM/Tool nodes and durable asynchronous
  scheduling remain follow-up.
