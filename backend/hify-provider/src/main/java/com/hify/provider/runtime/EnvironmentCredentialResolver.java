package com.hify.provider.runtime;

import org.springframework.stereotype.Component;
import com.hify.common.CredentialReferencePolicy;

@Component
public class EnvironmentCredentialResolver implements CredentialResolver {
    private final CredentialReferencePolicy policy;
    public EnvironmentCredentialResolver(CredentialReferencePolicy policy) { this.policy = policy; }
    @Override
    public String resolve(String credentialRef, String endpoint) {
        return policy.resolve(credentialRef, endpoint);
    }
}
