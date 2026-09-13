package com.hify.provider.runtime;

public interface CredentialResolver {
    String resolve(String credentialRef);
}
