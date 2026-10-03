# Credential reference boundary (review A1)

Task: SPEC-CREDENTIAL-BOUNDARY-001. Baseline 4955ca8, code 70a5f33 / spec ebeb661. Tests use fake properties, fake tokens and loopback upstreams only. No real process secret was resolved or sent, no hify-cc/shared deployment changes.

Evidence: `harness/evidence/SPEC-CREDENTIAL-BOUNDARY-001/SPEC-CREDENTIAL-BOUNDARY-001-20261003T175759Z-426f4174/`.

## Red

`red.log`: old code runs eight tests, four fail: Provider arbitrary property resolution, MCP creation with a protected reference, legacy reference use at runtime, and KEEP credential on endpoint change. Maven used `maven.test.failure.ignore=true` to reach both modules, so its exit code is zero despite the four assertion failures; the test counts, not BUILD SUCCESS, are the red evidence.

## Fix and focused green

- `CredentialReferencePolicy` defaults to deny all. Operator-only exact reference/target bindings, protected infrastructure names rejected, no wildcard grants. Save and runtime checks in MCP and Provider; every resolver receives its destination.
- Existing unapproved references fail closed. TOKEN encryption/version snapshots remain unchanged; changing MCP endpoint while implicitly retaining credentials fails without changing the row.
- `green-initial.log`: test-source compile error (AssertJ method overload), corrected. Not a product/test pass.
- `green-second.log`: 37 tests pass, zero failures/errors/skips (policy four, Provider boundary one, native adapters six; app 26). Includes real loopback MCP authorization and zero extra requests after attempting to retarget the reference.
- `provider-http.log`: four HTTP/H2 tests pass, including new save/update/runtime legacy-row denial. Legal OpenAI/Anthropic/Gemini requests still carry only the approved fake credential.

## Final atomic gate: passed

Atomic command: 34 tests pass, zero skip (policy four, Provider/native seven, app 23); the additional Provider HTTP contract is separately four pass in `provider-http.log`. Migration gate: 16 pass, zero skip (PostgreSQL concurrency six, MCP credentials seven, Workflow upgrade one, plus two H2 migrations). Harness state/generated progress/API spec/five Python tests/shell syntax all pass. See verification.json, finished 2026-10-03T18:10:13Z.

This does not claim deployment, real credential validation, all-system regression, login/RBAC, or DNS rebinding protection. Deployment must approve existing reference/destination pairs explicitly, not read/export values or automatically whitelist all database entries.

## G follow-up: protected namespaces and frozen legacy execution

Task `SPEC-CREDENTIAL-BOUNDARY-002`, baseline `a92c273`. Evidence: `harness/evidence/SPEC-CREDENTIAL-BOUNDARY-002/SPEC-CREDENTIAL-BOUNDARY-002-20261003T182421Z-0778be20/`.

- Red: CredentialReferencePolicyTest 4 run / 1 failed, exit 1. The first newly added `env:HIFY_DB_PASSWORD` operator grant was incorrectly accepted. Tests only parse names; no infrastructure value is read even on the red run.
- Protect HIFY_DB_, HIFY_REDIS_ and JAVAX_NET_SSL_ namespaces after case/dot/hyphen normalization. Test Hify URL/user/password names, TLS keyStore/trustStore passwords, normalized variants, and preserve legitimate explicitly granted application names containing SECRET/PASSWORD.
- Provider trailing-slash grants are not broadened. Test the saved base URL and document the exact canonical spelling; MCP retains its exact raw path distinction.
- New inherited H2/PostgreSQL contract publishes an Agent, simulates a pre-policy unapproved fake reference in its frozen MCP revision (draft remains credential-free), and invokes the published capability through ToolRuntime -> McpCapabilityService -> client. Expect error without fake secret in result/logs and **zero additional HTTP requests**, not merely a missing Authorization header. Existing positive frozen TOKEN tests remain.
- Code `5f4566c`. Focused green command selects CredentialReferencePolicyTest, ProviderCredentialBoundaryTest, NativeProviderModelClientTest, McpCredentialIntegrationTest, McpServerApiIntegrationTest, McpProtocolClientReliabilityTest and ProviderApiIntegrationTest, using `-pl hify-app -am -Dsurefire.failIfNoSpecifiedTests=false`: **36 pass, zero skip**, exit 0, see green.log. Atomic runner repeats all 36 successfully. Harness and migration gates passed at 2026-10-03T18:28:40Z: **17 pass, zero skip** (MCP PostgreSQL eight, PostgreSQL concurrency six, Workflow PG upgrade one, H2 migration two). See verification.json. This includes both H2 and PostgreSQL frozen legacy zero-request checks and unchanged positive encrypted-TOKEN snapshot checks.

No shared hify-cc worktree, service or data is changed. These fixes have not been deployed, and no new claim is made about DNS rebinding or operator-chosen unprotected custom variable names.

## G second follow-up: relaxed master-key aliases

Task `SPEC-CREDENTIAL-BOUNDARY-003`, baseline `ca56fd2`. Evidence: `harness/evidence/SPEC-CREDENTIAL-BOUNDARY-003/SPEC-CREDENTIAL-BOUNDARY-003-20261003T190227Z-914e7df7/`.

The reviewer identified Spring relaxed-binding aliases that bypassed the two exact master-key name checks. Parse-only red test: six cases run, one fails on `env:HIFY_MCP_CREDENTIALS_MASTERKEY`, exit 1; no value lookup. The fix reserves entire normalized HIFY_MCP_, HIFY_CREDENTIAL_ and HIFY_CREDENTIALS_ namespaces, covering camelCase masterKey, MASTERKEY, backup variants and authorization configuration itself. Eight names are exercised in the regression, while dedicated non-infrastructure application token names remain grantable.

Focused command: `mvn -B -f backend/pom.xml -pl hify-app -am -Dtest=CredentialReferencePolicyTest,ProviderCredentialBoundaryTest,McpCredentialIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`. **16 pass, zero skip**, exit 0, green.log. Includes all eight H2 MCP credential cases and published legacy-reference rejection before HTTP, plus unchanged encrypted TOKEN behavior. No schema/storage changes. PostgreSQL migration was not rerun in this alias-only slice (previous slice records that coverage). Operations/ADR describe reserved names and fail-closed migration of old immutable versions; there is no automatic historical snapshot rewrite.
