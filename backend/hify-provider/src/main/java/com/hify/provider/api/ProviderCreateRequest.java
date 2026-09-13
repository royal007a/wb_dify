package com.hify.provider.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProviderCreateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull ProviderType type,
        @Size(max = 2048) String baseUrl,
        @NotNull @Valid ProviderAuthInput auth,
        Boolean enabled,
        @NotEmpty List<@Valid ProviderModelInput> models
) {
    public ProviderCreateRequest {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
