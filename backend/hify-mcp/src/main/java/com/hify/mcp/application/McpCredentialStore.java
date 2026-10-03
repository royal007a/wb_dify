package com.hify.mcp.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.CredentialReferencePolicy;
import com.hify.mcp.api.McpServerRequest;
import com.hify.mcp.domain.McpCredential;
import com.hify.mcp.infrastructure.McpCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class McpCredentialStore {
    private static final Pattern REFERENCE = Pattern.compile("(?:env:[A-Za-z_][A-Za-z0-9_]*|system:[A-Za-z_][A-Za-z0-9_.-]*)");
    private final McpCredentialRepository repository;
    private final McpCredentialCipher cipher;
    private final CredentialReferencePolicy references;
    public McpCredentialStore(McpCredentialRepository repository, McpCredentialCipher cipher, CredentialReferencePolicy references) {
        this.repository = repository;
        this.cipher = cipher;
        this.references = references;
    }
    @Transactional
    public String apply(String serverId, String currentRef, McpServerRequest request) {
        String action = request.credentialAction();
        if (action == null) {
            if (request.credentialToken() != null) throw invalid("填写 Token 时必须选择 TOKEN 操作");
            action = request.credentialRef() == null ? "KEEP" : request.credentialRef().isBlank() ? "CLEAR" : "REFERENCE";
        }
        String ref = request.credentialRef();
        String token = request.credentialToken();
        if (!"TOKEN".equals(action) && token != null) throw invalid("该凭据操作不能同时提交 Token");
        if (!"REFERENCE".equals(action) && ref != null && !ref.isBlank()) throw invalid("该凭据操作不能同时提交引用");
        return switch (action) {
            case "KEEP" -> currentRef;
            case "CLEAR" -> null;
            case "REFERENCE" -> {
                if (ref == null || !REFERENCE.matcher(ref.trim()).matches()) throw invalid("请输入 env:变量名 或 system:属性名");
                references.requireAllowed(ref.trim(), request.endpointUrl());
                yield ref.trim();
            }
            case "TOKEN" -> {
                if (token == null || !token.matches("[A-Za-z0-9\\-._~+/]+=*") || token.length() > 8192)
                    throw invalid("请输入有效 Token，不含 Bearer 前缀、空格或换行");
                String id = UUID.randomUUID().toString();
                repository.save(new McpCredential(id, serverId, cipher.encrypt(serverId, id, token), Instant.now()));
                yield "stored:" + id;
            }
            default -> throw invalid("未知的凭据操作");
        };
    }
    @Transactional(readOnly = true)
    public String resolve(String serverId, String ref) {
        McpCredential credential = repository.findByIdAndServerId(ref.substring("stored:".length()), serverId)
                .orElseThrow(() -> new BizException(ErrorCode.CONFLICT, "MCP stored credential is unavailable"));
        return cipher.decrypt(serverId, credential.getId(), credential.getEncryptedValue());
    }
    public static String mode(String ref) {
        if (ref == null || ref.isBlank()) return "NONE";
        if (ref.startsWith("stored:")) return "TOKEN";
        return REFERENCE.matcher(ref).matches() ? "REFERENCE" : "UNAVAILABLE";
    }
    public static String publicReference(String ref) { return "REFERENCE".equals(mode(ref)) ? ref : null; }
    private static BizException invalid(String message) { return new BizException(ErrorCode.PARAM_ERROR, message); }
}
