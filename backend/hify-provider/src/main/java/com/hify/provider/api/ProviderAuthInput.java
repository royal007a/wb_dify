package com.hify.provider.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProviderAuthInput(
        @NotBlank @Size(max = 255) String credentialRef,
        @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") String headerName,
        @Size(max = 32) String prefix
) {}
