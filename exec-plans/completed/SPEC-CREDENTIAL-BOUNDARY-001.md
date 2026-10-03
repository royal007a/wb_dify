# Credential reference destination boundary

Scope: close reviewer A1 and the identical Provider process-secret resolver path. No authentication redesign, no actual secret export, no changes to hify-cc or deployed credentials.

1. Red: fake system property and local fake MCP prove arbitrary reference disclosure / endpoint KEEP; master key names rejected without reading values.
2. Shared policy: operator-only JSON reference -> exact approved endpoints, default empty/deny; validate before reading process secrets, also on legacy runtime paths. Reject reserved process-secret names and wildcard destinations. Provider uses the same guard.
3. Save + execute checks; MCP endpoint edits with credentials require explicit TOKEN/REFERENCE/CLEAR. Preserve frozen old credential+endpoint pairs; do not mutate published versions.
4. Unit + HTTP/H2 + existing real PostgreSQL credential matrix, including zero outbound requests for rejected legacy references.
5. Update API/security/ADR and evidence. Deployment must inventory reference names/endpoints only and record deliberate allow-list migration; never infer wildcard grants or print secrets.

Rollback: code revert, but never deploy an older resolver to an untrusted network. No schema/data/key migration in this atomic task.
