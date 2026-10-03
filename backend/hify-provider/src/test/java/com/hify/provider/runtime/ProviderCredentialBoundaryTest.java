package com.hify.provider.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderCredentialBoundaryTest {
    @Test void providerGrantsUseBaseUrlWithTrailingSlashRemoved() {
        String ref = "system:hify.review.provider-key";
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var urlPolicy = new com.hify.provider.application.ProviderUrlPolicy(true, "");
        String storedUrl = urlPolicy.validate("https://approved.example/v1/");
        assertThat(storedUrl).isEqualTo("https://approved.example/v1");
        var wrong = new com.hify.common.CredentialReferencePolicy("""
                {"system:hify.review.provider-key":["https://approved.example/v1/"]}
                """, json);
        assertThatThrownBy(() -> wrong.requireAllowed(ref, storedUrl)).isInstanceOf(com.hify.common.BizException.class);
        var correct = new com.hify.common.CredentialReferencePolicy("""
                {"system:hify.review.provider-key":["https://approved.example/v1"]}
                """, json);
        assertThatCode(() -> correct.requireAllowed(ref, storedUrl)).doesNotThrowAnyException();
    }

    @Test void unlistedProcessPropertyCannotBeUsedAsProviderCredential() {
        System.setProperty("hify.review.provider-secret", "fake-private-process-value");
        try {
            var policy = new com.hify.common.CredentialReferencePolicy("", new com.fasterxml.jackson.databind.ObjectMapper());
            assertThatThrownBy(() -> new EnvironmentCredentialResolver(policy).resolve("system:hify.review.provider-secret", "https://untrusted.example/v1"))
                    .isInstanceOf(RuntimeException.class).hasMessageNotContaining("fake-private-process-value");
        } finally { System.clearProperty("hify.review.provider-secret"); }
    }
}
