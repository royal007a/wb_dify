---
name: hify-module-delivery
description: Design and deliver one Hify business module from explicit decisions through backend, frontend, tests, documentation, and split commits. Use for Provider, Agent, Chat, Tool, MCP, Knowledge, or Workflow feature slices; not for tiny isolated fixes.
---

# Hify Module Delivery

Deliver one reviewable vertical slice. Preserve the repository's `AGENTS.md`, ADRs, API contracts, security boundaries, and existing user changes.

## 1. Establish the domain before editing

Answer four questions from repository evidence and the requested outcome:

1. What is the capability and which problem does it solve?
2. Which user flow uses it, and what is the smallest useful slice?
3. Which entities, invariants, states, and external boundaries are necessary now?
4. How does it fit the current modules, data flow, and runtime?

Inspect source, tests, migrations, API types, UI components, and current-state documents. Label current behavior versus target behavior. Do not infer that a design document is already implemented.

## 2. Record consequential decisions

For each decision that affects data compatibility, runtime safety, security, or later evolution, record:

- considered options;
- chosen option and current constraints;
- rejected options and the observable trigger for reconsidering them;
- resulting invariants and acceptance evidence.

Prefer the simplest design that preserves correctness. Examples: immutable Agent releases, credential references instead of secrets, stateful conversations, SSE for server-to-client streaming, and database-backed replay.

## 3. Define contracts and acceptance first

Before broad implementation, establish the API shape, persistence migration, error semantics, and tests for the risky invariant. Keep current-state and target-state terms explicit.

Use this dependency order when applicable:

1. migration and domain invariants;
2. entity/repository or mapper;
3. request/response contracts;
4. application service and transaction boundary;
5. transport controller;
6. frontend API and page/component;
7. end-to-end verification and documentation.

Split further whenever output exceeds one practical review unit or a later step depends on an earlier one.

## 4. Implement inside module boundaries

- Keep controllers thin and return the common response contract.
- Put use-case orchestration and transactions in application services.
- Reuse `PageResult`, validation, caches, executors, HTTP clients, resilience, and UI components.
- Do not create placeholder layers or abstractions without at least one real consumer.
- Keep provider-specific wire formats in provider adapters.
- Pin runtime work to immutable configuration snapshots when mutable edits could change historical behavior.
- Never persist plaintext credentials; accept credential references only.
- Do not hold a database transaction open across an LLM call or SSE lifetime.

## 5. Verify at each boundary

Run the narrowest meaningful test after every dependent stage, then run the full relevant matrix. Verification must include the primary success path and the risk that motivated the design.

Typical evidence:

- migration succeeds on H2 PostgreSQL mode and real PostgreSQL when available;
- service/controller integration test proves invariants and error mapping;
- native protocol contract test proves request and response translation;
- frontend typecheck and production build pass;
- packaged JAR starts and `/api/v1/health` returns 200;
- local deployment scripts start and stop cleanly.

Report unavailable checks as unavailable, never as passed.

## 6. Close the SDD loop

When implementation reveals a repeatable failure mode, correct the code and update the narrowest durable rule: test, ADR, API/data document, this skill, or `AGENTS.md`. Do not add one-off anecdotes as universal rules.

Update `CURRENT_STATE`, implementation plan, and module docs with verified facts. Move remaining gaps to `plan.md` with observable completion criteria.

## 7. Commit by concern

Check `git diff --check` and keep temporary/rendered artifacts out of commits. Prefer independently reviewable commits such as:

- backend domain/runtime behavior;
- frontend delivery;
- tests/operations when separable;
- documentation and reusable process.

Do not push unless the user requested it. Before push, run the agreed verification matrix and show the exact remaining caveats.
