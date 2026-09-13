package com.hify.api;

import com.hify.common.PageResult;
import com.hify.common.Result;
import com.hify.provider.api.ConnectionTestResponse;
import com.hify.provider.api.ProviderCreateRequest;
import com.hify.provider.api.ProviderResponse;
import com.hify.provider.api.ProviderService;
import com.hify.provider.api.ProviderType;
import com.hify.provider.api.ProviderUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {
    private final ProviderService providers;

    public ProviderController(ProviderService providers) {
        this.providers = providers;
    }

    @GetMapping
    public PageResult<ProviderResponse> list(
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(100) Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ProviderType type,
            @RequestParam(required = false) Boolean enabled) {
        return providers.list(page, pageSize, keyword, type, enabled);
    }

    @PostMapping
    public ResponseEntity<Result<String>> create(@Valid @RequestBody ProviderCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(providers.create(request)));
    }

    @GetMapping("/{providerId}")
    public Result<ProviderResponse> get(@PathVariable String providerId) {
        return Result.ok(providers.get(providerId));
    }

    @PutMapping("/{providerId}")
    public Result<Void> update(@PathVariable String providerId,
                               @Valid @RequestBody ProviderUpdateRequest request) {
        providers.update(providerId, request);
        return Result.ok();
    }

    @DeleteMapping("/{providerId}")
    public Result<Void> delete(@PathVariable String providerId) {
        providers.delete(providerId);
        return Result.ok();
    }

    @PostMapping("/{providerId}/connection-tests")
    public Result<ConnectionTestResponse> testConnection(@PathVariable String providerId) {
        return Result.ok(providers.testConnection(providerId));
    }
}
