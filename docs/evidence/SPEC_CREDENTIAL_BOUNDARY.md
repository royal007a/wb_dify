# Credential reference boundary (review A1)

Task: SPEC-CREDENTIAL-BOUNDARY-001. Baseline 4955ca8, review target to be recorded after commit. Tests use fake properties, fake tokens and loopback upstreams only. No real process secret was resolved or sent, no hify-cc/shared deployment changes.

Evidence: `harness/evidence/SPEC-CREDENTIAL-BOUNDARY-001/SPEC-CREDENTIAL-BOUNDARY-001-20261003T175759Z-426f4174/`.

## Red

`red.log`: old code runs eight tests, four fail: Provider arbitrary property resolution, MCP creation with a protected reference, legacy reference use at runtime, and KEEP credential on endpoint change. Maven used `maven.test.failure.ignore=true` to reach both modules, so its exit code is zero despite the four assertion failures; the test counts, not BUILD SUCCESS, are the red evidence.

## Fix and focused green

- `CredentialReferencePolicy` defaults to deny all. Operator-only exact reference/target bindings, protected infrastructure names rejected, no wildcard grants. Save and runtime checks in MCP and Provider; every resolver receives its destination.
- Existing unapproved references fail closed. TOKEN encryption/version snapshots remain unchanged; changing MCP endpoint while implicitly retaining credentials fails without changing the row.
- `green-initial.log`: test-source compile error (AssertJ method overload), corrected. Not a product/test pass.
- `green-second.log`: 37 tests pass, zero failures/errors/skips (policy four, Provider boundary one, native adapters six; app 26). Includes real loopback MCP authorization and zero extra requests after attempting to retarget the reference.
- `provider-http.log`: four HTTP/H2 tests pass, including new save/update/runtime legacy-row denial. Legal OpenAI/Anthropic/Gemini requests still carry only the approved fake credential.

Atomic command and PostgreSQL migration gate are still pending; final result belongs in the Harness verification manifest. This does not claim deployment, real credential validation, all-system regression, login/RBAC, or DNS rebinding protection. Deployment must approve existing reference/destination pairs explicitly, not read/export values or automatically whitelist all database entries.
