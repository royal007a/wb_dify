package com.hify.api;

import com.hify.domain.ModelProvider;
import com.hify.domain.ProviderType;
import com.hify.infra.ModelProviderRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {
    private final ModelProviderRepository providers;

    public ProviderController(ModelProviderRepository providers) {
        this.providers = providers;
    }

    @GetMapping
    public List<ModelProvider> list() {
        return providers.findAll();
    }

    @PostMapping
    public ModelProvider create(@Valid @RequestBody CreateProvider request) {
        return providers.save(new ModelProvider(
                UUID.randomUUID().toString(), request.name(), request.type(), request.baseUrl(),
                request.apiKeyEnv(), request.defaultModel(), true));
    }

    public record CreateProvider(
            @NotBlank String name,
            @NotNull ProviderType type,
            String baseUrl,
            String apiKeyEnv,
            @NotBlank String defaultModel
    ) {}
}

