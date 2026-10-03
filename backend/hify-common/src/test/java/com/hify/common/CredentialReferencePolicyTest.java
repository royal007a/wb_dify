package com.hify.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CredentialReferencePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void deniesAllUnconfiguredReferencesWithoutLookingThemUp() {
        var policy = new CredentialReferencePolicy("", json);
        for (String ref : List.of("env:HIFY_MCP_MASTER_KEY", "env:SPRING_DATASOURCE_PASSWORD",
                "system:user.home", "system:hify.mcp.credentials.master-key", "env:APP_TOOL_TOKEN"))
            assertThatThrownBy(() -> policy.resolve(ref, "https://example.com/mcp"))
                    .isInstanceOf(BizException.class).hasMessageNotContaining(ref);
    }

    @Test void onlyExplicitReferenceAndExactDestinationCanReadFakeProperty() {
        var policy = new CredentialReferencePolicy("""
                {"system:hify.test.safe-token":["https://approved.example/mcp"]}
                """, json);
        System.setProperty("hify.test.safe-token", "fake-allowed-value");
        try {
            assertThat(policy.resolve("system:hify.test.safe-token", "https://APPROVED.example:443/mcp")).isEqualTo("fake-allowed-value");
            for (String endpoint : List.of("https://evil.example/mcp", "http://approved.example/mcp",
                    "https://approved.example:444/mcp", "https://approved.example/mcp/other",
                    "https://approved.example/mcp?q=x", "https://approved.example/mcp#x",
                    "https://approved.example@evil.example/mcp", "https://approved.example/a/../mcp"))
                assertThatThrownBy(() -> policy.resolve("system:hify.test.safe-token", endpoint))
                        .isInstanceOf(BizException.class).hasMessageNotContaining(endpoint).hasMessageNotContaining("fake-allowed-value");
        } finally { System.clearProperty("hify.test.safe-token"); }
    }

    @Test void reservedSecretsCannotBeEnabledEvenByMisconfiguredOperator() throws Exception {
        for (String ref : List.of("env:HIFY_MCP_MASTER_KEY", "system:HIFY_MCP_MASTER_KEY",
                "system:hify.mcp.credentials.master-key", "env:DB_PASSWORD", "env:SPRING_DATASOURCE_PASSWORD")) {
            String config = json.writeValueAsString(java.util.Map.of(ref, List.of("https://approved.example/mcp")));
            assertThatThrownBy(() -> new CredentialReferencePolicy(config, json))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(ref).hasNoCause();
        }
    }

    @Test void malformedOrWildcardOperatorConfigFailsClosedWithoutEcho() {
        for (String config : List.of("not-json-fake-secret", "null", "{\"system:fake\":[\"https://*.example/mcp\"]}",
                "{\"system:fake\":[\"https://u:fake-secret@example.com/mcp\"]}", "{\"system:fake\":null}"))
            assertThatThrownBy(() -> new CredentialReferencePolicy(config, json))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("fake-secret").hasNoCause();
    }
}
