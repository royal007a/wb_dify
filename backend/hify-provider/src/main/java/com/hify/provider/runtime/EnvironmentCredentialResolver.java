package com.hify.provider.runtime;

import org.springframework.stereotype.Component;

@Component
public class EnvironmentCredentialResolver implements CredentialResolver {
    @Override
    public String resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalStateException("Provider credentialRef is missing");
        }
        String value;
        if (credentialRef.startsWith("env:")) {
            value = System.getenv(credentialRef.substring(4));
        } else if (credentialRef.startsWith("system:")) {
            value = System.getProperty(credentialRef.substring(7));
        } else {
            throw new IllegalStateException("Unsupported credentialRef scheme");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Provider credential is unavailable");
        }
        return value;
    }
}
