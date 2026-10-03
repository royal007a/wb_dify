package com.hify.mcp.application;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.CredentialReferencePolicy;
import org.springframework.stereotype.Component;

@Component
public class McpCredentialResolver {
    private final McpCredentialStore store;
    private final CredentialReferencePolicy references;
    public McpCredentialResolver(McpCredentialStore store, CredentialReferencePolicy references) { this.store = store; this.references = references; }
    public String resolve(String serverId, String ref) {
        return resolve(serverId, ref, null); // Historical stored-token inspection; references require a destination.
    }
    public String resolve(String serverId, String ref, String endpoint) {
        if (ref == null || ref.isBlank()) return null;
        String value;
        if (ref.startsWith("stored:")) value = store.resolve(serverId, ref);
        else if (ref.startsWith("env:") || ref.startsWith("system:")) value = references.resolve(ref, endpoint);
        else throw new BizException(ErrorCode.PARAM_ERROR, "MCP credential reference is invalid; please replace it");
        if (value == null || value.isBlank()) throw new BizException(ErrorCode.PARAM_ERROR, "MCP credential is unavailable");
        if (!value.matches("[A-Za-z0-9\\-._~+/]+=*")) throw new BizException(ErrorCode.PARAM_ERROR, "MCP credential is not a valid Bearer token");
        return value;
    }
}
