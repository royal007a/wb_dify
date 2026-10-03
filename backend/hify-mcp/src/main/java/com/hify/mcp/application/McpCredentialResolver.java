package com.hify.mcp.application;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class McpCredentialResolver {
    private final McpCredentialStore store;
    public McpCredentialResolver(McpCredentialStore store) { this.store = store; }
    public String resolve(String serverId, String ref) {
        if (ref == null || ref.isBlank()) return null;
        String value;
        if (ref.startsWith("stored:")) value = store.resolve(serverId, ref);
        else if (ref.startsWith("env:")) value = System.getenv(ref.substring(4));
        else if (ref.startsWith("system:")) value = System.getProperty(ref.substring(7));
        else throw new BizException(ErrorCode.PARAM_ERROR, "MCP credential reference is invalid; please replace it");
        if (value == null || value.isBlank()) throw new BizException(ErrorCode.PARAM_ERROR, "MCP credential is unavailable");
        if (!value.matches("[A-Za-z0-9\\-._~+/]+=*")) throw new BizException(ErrorCode.PARAM_ERROR, "MCP credential is not a valid Bearer token");
        return value;
    }
}
