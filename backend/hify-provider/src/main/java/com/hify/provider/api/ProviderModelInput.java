package com.hify.provider.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProviderModelInput(
        @NotBlank @Size(max = 255) String displayName,
        @NotBlank @Size(max = 255) String modelId,
        @NotNull Boolean enabled,
        @NotNull Boolean isDefault
) {}
