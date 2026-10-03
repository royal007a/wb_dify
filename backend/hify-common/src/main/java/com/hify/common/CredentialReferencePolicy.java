package com.hify.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Operator-owned grants. API callers cannot choose arbitrary process secrets or destinations. */
@Component
public class CredentialReferencePolicy {
    private static final Pattern REF = Pattern.compile("(?:env:[A-Za-z_][A-Za-z0-9_]*|system:[A-Za-z_][A-Za-z0-9_.-]*)");
    private final Map<String, Set<String>> grants;

    public CredentialReferencePolicy(@Value("${hify.credentials.reference-bindings:}") String config,
                                     ObjectMapper json) {
        try {
            Map<String, List<String>> input = config == null || config.isBlank() ? Map.of()
                    : json.readValue(config, new TypeReference<>() {});
            Map<String, Set<String>> parsed = new HashMap<>();
            for (var entry : input.entrySet()) {
                if (!validReference(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty())
                    throw new IllegalArgumentException();
                Set<String> targets = new HashSet<>();
                for (String endpoint : entry.getValue()) targets.add(target(endpoint));
                parsed.put(entry.getKey(), Set.copyOf(targets));
            }
            grants = Map.copyOf(parsed);
        } catch (Exception ignored) {
            // Configuration parsing exceptions often contain the supplied value. Never chain them.
            throw new IllegalStateException("Credential reference bindings are invalid; check operator configuration");
        }
    }

    public void requireAllowed(String ref, String endpoint) {
        boolean allowed = false;
        try { allowed = validReference(ref) && grants.getOrDefault(ref, Set.of()).contains(target(endpoint)); }
        catch (RuntimeException ignored) { /* fail closed without reflecting caller input */ }
        if (!allowed) throw new BizException(ErrorCode.PARAM_ERROR,
                "Credential reference is not approved for this endpoint; contact the administrator");
    }

    public String resolve(String ref, String endpoint) {
        requireAllowed(ref, endpoint); // Must precede getenv/getProperty, including legacy database rows.
        String value = ref.startsWith("env:") ? System.getenv(ref.substring(4)) : System.getProperty(ref.substring(7));
        if (value == null || value.isBlank())
            throw new BizException(ErrorCode.PARAM_ERROR, "Credential is unavailable");
        return value;
    }

    private static boolean validReference(String ref) {
        if (ref == null || !REF.matcher(ref).matches()) return false;
        String name = ref.substring(ref.indexOf(':') + 1).replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        // These are infrastructure secrets, never provider/tool credentials even if mistakenly listed.
        return !name.equals("HIFY_MCP_MASTER_KEY") && !name.equals("HIFY_MCP_CREDENTIALS_MASTER_KEY")
                && !name.startsWith("SPRING_") && !name.startsWith("DB_") && !name.startsWith("DATABASE_")
                && !name.startsWith("HIFY_DB_") && !name.startsWith("HIFY_REDIS_")
                && !name.startsWith("JAVAX_NET_SSL_");
    }

    private static String target(String value) {
        URI uri = URI.create(Objects.requireNonNull(value).trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || value.contains("*") || !uri.normalize().equals(uri)) throw new IllegalArgumentException();
        int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException();
        String path = uri.getRawPath();
        if (path == null) path = "";
        // No prefix/subdomain/path wildcard matching. Ports and endpoint paths are part of the grant.
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port + path;
    }
}
