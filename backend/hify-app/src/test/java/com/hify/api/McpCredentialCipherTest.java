package com.hify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.mcp.api.McpServerRequest;
import com.hify.mcp.application.McpCredentialCipher;
import com.hify.mcp.application.McpCredentialResolver;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class McpCredentialCipherTest {
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    @Test void roundTripUsesRandomNoncesAndIsRestartStable() {
        var cipher = new McpCredentialCipher(KEY);
        String one = cipher.encrypt("server", "id", "fake-test-token");
        String two = cipher.encrypt("server", "id", "fake-test-token");
        assertThat(one).doesNotContain("fake-test-token").isNotEqualTo(two);
        assertThat(new McpCredentialCipher(KEY).decrypt("server", "id", one)).isEqualTo("fake-test-token");
    }
    @Test void rejectsTamperingWrongKeyAndWrongOwner() {
        var cipher = new McpCredentialCipher(KEY);
        String value = cipher.encrypt("server", "id", "fake-test-token");
        assertThatThrownBy(() -> cipher.decrypt("other", "id", value)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> cipher.decrypt("server", "other", value)).isInstanceOf(BizException.class);
        String tampered = value.substring(0, value.lastIndexOf(':') + 1) + "AAAA";
        assertThatThrownBy(() -> cipher.decrypt("server", "id", tampered)).isInstanceOf(BizException.class);
        byte[] wrong = new byte[32]; wrong[0] = 1;
        assertThatThrownBy(() -> new McpCredentialCipher(Base64.getEncoder().encodeToString(wrong))
                .decrypt("server", "id", value)).isInstanceOf(BizException.class).hasMessageNotContaining(value);
    }
    @Test void missingOrMalformedKeyFailsClosedWithoutEcho() {
        assertThatThrownBy(() -> new McpCredentialCipher("").encrypt("s", "i", "fake-secret"))
                .isInstanceOf(BizException.class).hasMessageNotContaining("fake-secret");
        assertThatThrownBy(() -> new McpCredentialCipher("invalid-key"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("invalid-key");
        assertThatThrownBy(() -> new McpCredentialCipher("AAAA"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("AAAA");
    }
    @Test void dtoDoesNotSerializeOrPrintToken() throws Exception {
        var dto = new McpServerRequest("demo", "https://example.com/mcp", null, true, "TOKEN", "fake-test-token");
        assertThat(dto.toString()).doesNotContain("fake-test-token");
        assertThat(new ObjectMapper().writeValueAsString(dto)).doesNotContain("fake-test-token", "credentialToken");
    }
    @Test void systemReferenceStillWorksAndInvalidHeadersAreRejected() {
        var resolver = new McpCredentialResolver(null);
        System.setProperty("hify.test.mcp.token", "fake-system-token");
        try {
            assertThat(resolver.resolve("s", "system:hify.test.mcp.token")).isEqualTo("fake-system-token");
            System.setProperty("hify.test.mcp.token", "bad\r\nheader");
            assertThatThrownBy(() -> resolver.resolve("s", "system:hify.test.mcp.token"))
                    .isInstanceOf(BizException.class).hasMessageNotContaining("header");
            assertThatThrownBy(() -> resolver.resolve("s", "env:HIFY_NONEXISTENT_TEST_ONLY_TOKEN"))
                    .isInstanceOf(BizException.class).hasMessageContaining("unavailable");
        } finally { System.clearProperty("hify.test.mcp.token"); }
    }
}
