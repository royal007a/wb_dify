# G protected configuration aliases

Add parse-only red cases for HIFY_MCP_CREDENTIALS_MASTERKEY and hify.mcp.credentials.masterKey, plus explicit prefix variants. Protect the full HIFY_MCP_ and HIFY_CREDENTIAL_ configuration namespaces after name normalization. Legitimate externally sent application tokens must use dedicated non-infrastructure names, explicitly granted to destinations. Update operations migration notes; do not read actual system/environment values.

Run policy and Provider/MCP boundary regressions; no persistence format changes or shared deployment mutations.
