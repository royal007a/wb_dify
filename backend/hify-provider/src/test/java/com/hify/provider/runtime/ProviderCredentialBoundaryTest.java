package com.hify.provider.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderCredentialBoundaryTest {
    @Test void unlistedProcessPropertyCannotBeUsedAsProviderCredential() {
        System.setProperty("hify.review.provider-secret", "fake-private-process-value");
        try {
            var policy = new com.hify.common.CredentialReferencePolicy("", new com.fasterxml.jackson.databind.ObjectMapper());
            assertThatThrownBy(() -> new EnvironmentCredentialResolver(policy).resolve("system:hify.review.provider-secret", "https://untrusted.example/v1"))
                    .isInstanceOf(RuntimeException.class).hasMessageNotContaining("fake-private-process-value");
        } finally { System.clearProperty("hify.review.provider-secret"); }
    }
}
