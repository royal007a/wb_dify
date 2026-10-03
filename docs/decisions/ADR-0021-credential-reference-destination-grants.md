# Restrict process credential references to operator-approved destinations

Context: independent review A1 found that arbitrary `env:` / `system:` names let a management API caller send process secrets (including the MCP encryption key) as outbound credentials. The Provider resolver had the same flaw. Encryption at rest did not prevent this.

Decision:

- A shared common policy loads operator-only `hify.credentials.reference-bindings` / `HIFY_CREDENTIAL_REFERENCE_BINDINGS` JSON. Exact reference -> approved endpoint/base URL list; default empty denies all. Validate on explicit configuration writes and immediately before resolving process values, including legacy records and frozen MCP revisions.
- Match scheme, case-insensitive hostname, effective port and exact raw path. No wildcard/subdomain/prefix grants; reject userinfo, query, fragment and dot-segment destinations. MCP protocol uses its endpoint; Provider passes its baseUrl before constructing a client.
- Always reject the HIFY_MCP_/HIFY_CREDENTIAL_/HIFY_CREDENTIALS_/SPRING_/DB_/DATABASE_/HIFY_DB_/HIFY_REDIS_/JAVAX_NET_SSL_ infrastructure namespaces, even if accidentally granted (case-insensitive after replacing dots/hyphens with underscores). Whole MCP/credential namespaces cover relaxed binding aliases such as masterKey/MASTERKEY, not just two exact master-key names. This also covers Hify's actual datasource settings and JVM keyStore/trustStore credentials. Do not reject every arbitrary name containing SECRET/PASSWORD: explicitly approved application credentials may legitimately use those names. No diagnostics include supplied configuration, reference values or secret values.
- Changing an MCP endpoint while retaining a credential implicitly is rejected. Require explicit replacement or CLEAR. Existing immutable TOKEN records and endpoint/revision pairs remain unchanged.
- This supersedes ADR-0020's unrestricted reference compatibility for both MCP and Provider. There is no permissive legacy fallback. Missing grants fail closed rather than silently exporting secrets.

Non-goals: not authentication, RBAC, global revocation, key rotation or a DNS rebinding fix. TOKEN input is still write-only. An authorized administrator must review existing reference/destination pairs before deployment; never export process secret values to generate grants. Reverting to unrestricted resolution is not a safe network-facing rollback.
