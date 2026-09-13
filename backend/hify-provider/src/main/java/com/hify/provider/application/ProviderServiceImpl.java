package com.hify.provider.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.PageHelper;
import com.hify.common.PageResult;
import com.hify.provider.api.ConnectionTestResponse;
import com.hify.provider.api.ProviderBootstrapService;
import com.hify.provider.api.ProviderCreateRequest;
import com.hify.provider.api.ProviderHealthResponse;
import com.hify.provider.api.ProviderModelInput;
import com.hify.provider.api.ProviderModelResponse;
import com.hify.provider.api.ProviderQueryService;
import com.hify.provider.api.ProviderResponse;
import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.api.ProviderService;
import com.hify.provider.api.ProviderType;
import com.hify.provider.api.ProviderUpdateRequest;
import com.hify.provider.entity.ProviderEntity;
import com.hify.provider.entity.ProviderHealthEntity;
import com.hify.provider.entity.ProviderModelEntity;
import com.hify.provider.mapper.ProviderHealthMapper;
import com.hify.provider.mapper.ProviderMapper;
import com.hify.provider.mapper.ProviderModelMapper;
import com.hify.provider.runtime.ProviderAdapterRegistry;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProviderServiceImpl implements ProviderService, ProviderQueryService, ProviderBootstrapService {
    private final ProviderMapper providers;
    private final ProviderModelMapper models;
    private final ProviderHealthMapper health;
    private final ProviderAuthConfigCodec authCodec;
    private final ProviderUrlPolicy urlPolicy;
    private final ProviderAdapterRegistry adapters;

    public ProviderServiceImpl(ProviderMapper providers, ProviderModelMapper models,
                               ProviderHealthMapper health, ProviderAuthConfigCodec authCodec,
                               ProviderUrlPolicy urlPolicy, ProviderAdapterRegistry adapters) {
        this.providers = providers;
        this.models = models;
        this.health = health;
        this.authCodec = authCodec;
        this.urlPolicy = urlPolicy;
        this.adapters = adapters;
    }

    @Override
    @Transactional
    public String create(ProviderCreateRequest request) {
        requireManageable(request.type());
        ensureNameAvailable(request.name(), null);
        ValidatedModels validated = validateModels(request.models());
        ProviderEntity provider = new ProviderEntity();
        provider.setPublicId(UUID.randomUUID().toString());
        provider.setName(request.name().trim());
        provider.setType(request.type().name());
        provider.setBaseUrl(resolveBaseUrl(request.type(), request.baseUrl()));
        provider.setAuthConfig(authCodec.encode(request.type(), request.auth()));
        provider.setDefaultModelId(validated.defaultModelId());
        provider.setEnabled(!Boolean.FALSE.equals(request.enabled()));
        providers.insert(provider);
        replaceModels(provider.getId(), validated.models());
        insertUnknownHealth(provider.getId());
        return provider.getPublicId();
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderResponse get(String publicId) {
        ProviderEntity provider = requireProvider(publicId);
        return response(provider, listModels(provider.getId()), findHealth(provider.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ProviderResponse> list(Integer page, Integer pageSize, String keyword,
                                             ProviderType type, Boolean enabled) {
        Page<ProviderEntity> query = PageHelper.toPage(page, pageSize);
        LambdaQueryWrapper<ProviderEntity> where = new LambdaQueryWrapper<ProviderEntity>()
                .eq(type != null, ProviderEntity::getType, type == null ? null : type.name())
                .eq(enabled != null, ProviderEntity::getEnabled, enabled)
                .and(keyword != null && !keyword.isBlank(), nested -> nested
                        .like(ProviderEntity::getName, keyword == null ? null : keyword.trim())
                        .or().like(ProviderEntity::getPublicId, keyword == null ? null : keyword.trim()))
                .orderByDesc(ProviderEntity::getUpdatedAt);
        Page<ProviderEntity> result = providers.selectPage(query, where);
        List<Long> providerIds = result.getRecords().stream().map(ProviderEntity::getId).toList();
        Map<Long, List<ProviderModelEntity>> modelMap = listModels(providerIds).stream()
                .collect(Collectors.groupingBy(ProviderModelEntity::getProviderId));
        Map<Long, ProviderHealthEntity> healthMap = listHealth(providerIds).stream()
                .collect(Collectors.toMap(ProviderHealthEntity::getProviderId, Function.identity()));
        return PageHelper.toPageResult(result, provider -> response(provider,
                modelMap.getOrDefault(provider.getId(), List.of()), healthMap.get(provider.getId())));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "provider-cache", key = "#publicId")
    public void update(String publicId, ProviderUpdateRequest request) {
        requireManageable(request.type());
        ProviderEntity provider = requireProvider(publicId);
        if (!provider.getType().equals(request.type().name()) && request.auth() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "切换 Provider 类型时必须重新提交鉴权配置");
        }
        ensureNameAvailable(request.name(), provider.getId());
        ValidatedModels validated = validateModels(request.models());
        provider.setName(request.name().trim());
        provider.setType(request.type().name());
        provider.setBaseUrl(resolveBaseUrl(request.type(), request.baseUrl()));
        if (request.auth() != null) provider.setAuthConfig(authCodec.encode(request.type(), request.auth()));
        provider.setDefaultModelId(validated.defaultModelId());
        provider.setEnabled(request.enabled());
        providers.updateById(provider);
        replaceModels(provider.getId(), validated.models());
        resetHealth(provider.getId());
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "provider-cache", key = "#publicId")
    public void delete(String publicId) {
        ProviderEntity provider = requireProvider(publicId);
        provider.setEnabled(false);
        providers.updateById(provider);
        models.physicallyDeleteByProviderId(provider.getId());
        health.delete(new LambdaQueryWrapper<ProviderHealthEntity>()
                .eq(ProviderHealthEntity::getProviderId, provider.getId()));
        providers.deleteById(provider.getId());
    }

    @Override
    public ConnectionTestResponse testConnection(String publicId) {
        ProviderEntity provider = requireProvider(publicId);
        ProviderAdapterRegistry.AdapterHealth tested = adapters.test(runtimeConfig(provider));
        persistHealth(provider.getId(), tested);
        return new ConnectionTestResponse(tested.success(), tested.latencyMs(), tested.code(),
                tested.message(), tested.checkedAt());
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "provider-cache", key = "#publicId")
    public ProviderRuntimeConfig requireEnabled(String publicId) {
        ProviderEntity provider = requireProvider(publicId);
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new BizException(ErrorCode.CONFLICT, "Provider 已停用");
        }
        return runtimeConfig(provider);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String publicId) {
        return providers.selectCount(new LambdaQueryWrapper<ProviderEntity>()
                .eq(ProviderEntity::getPublicId, publicId)) > 0;
    }

    @Override
    @Transactional
    public void ensureDevelopmentMock() {
        if (exists("mock")) return;
        ProviderEntity provider = new ProviderEntity();
        provider.setPublicId("mock");
        provider.setName("Hify Mock");
        provider.setType(ProviderType.MOCK.name());
        provider.setBaseUrl(ProviderType.MOCK.defaultBaseUrl());
        provider.setAuthConfig("{\"version\":1,\"credentialRef\":null,\"headerName\":null,\"prefix\":null}");
        provider.setDefaultModelId("hify-mock");
        provider.setEnabled(true);
        providers.insert(provider);
        replaceModels(provider.getId(), List.of(new ProviderModelInput(
                "Hify Mock", "hify-mock", true, true)));
        insertUnknownHealth(provider.getId());
    }

    private ProviderRuntimeConfig runtimeConfig(ProviderEntity provider) {
        return new ProviderRuntimeConfig(provider.getPublicId(), provider.getName(),
                ProviderType.valueOf(provider.getType()), provider.getBaseUrl(),
                provider.getAuthConfig(), provider.getDefaultModelId(), provider.getEnabled());
    }

    private ProviderEntity requireProvider(String publicId) {
        ProviderEntity provider = providers.selectOne(new LambdaQueryWrapper<ProviderEntity>()
                .eq(ProviderEntity::getPublicId, publicId));
        if (provider == null) throw new BizException(ErrorCode.NOT_FOUND, "Provider 不存在");
        return provider;
    }

    private void requireManageable(ProviderType type) {
        if (!type.manageable()) throw new BizException(ErrorCode.PARAM_ERROR, "Mock Provider 不能通过管理 API 创建");
    }

    private String resolveBaseUrl(ProviderType type, String requested) {
        String value = requested == null || requested.isBlank() ? type.defaultBaseUrl() : requested.trim();
        if (value == null) throw new BizException(ErrorCode.PARAM_ERROR, "通用兼容 Provider 必须填写 Base URL");
        return urlPolicy.validate(value);
    }

    private void ensureNameAvailable(String name, Long currentId) {
        ProviderEntity existing = providers.selectOne(new LambdaQueryWrapper<ProviderEntity>()
                .eq(ProviderEntity::getName, name.trim()));
        if (existing != null && !existing.getId().equals(currentId)) {
            throw new BizException(ErrorCode.CONFLICT, "Provider 名称已存在");
        }
    }

    private ValidatedModels validateModels(List<ProviderModelInput> input) {
        if (input == null || input.isEmpty()) throw new BizException(ErrorCode.PARAM_ERROR, "至少配置一个模型");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        int defaults = 0;
        String defaultModelId = null;
        for (ProviderModelInput model : input) {
            String modelId = model.modelId().trim();
            if (!ids.add(modelId)) throw new BizException(ErrorCode.PARAM_ERROR, "modelId 不能重复");
            if (Boolean.TRUE.equals(model.isDefault())) {
                defaults++;
                defaultModelId = modelId;
                if (!Boolean.TRUE.equals(model.enabled())) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "默认模型必须启用");
                }
            }
        }
        if (defaults != 1) throw new BizException(ErrorCode.PARAM_ERROR, "必须且只能设置一个默认模型");
        return new ValidatedModels(List.copyOf(input), defaultModelId);
    }

    private void replaceModels(Long providerId, List<ProviderModelInput> input) {
        // Model rows are configuration children. Physical replacement avoids a logical-delete
        // tombstone colliding with the unique (provider_id, model_id) constraint.
        models.physicallyDeleteByProviderId(providerId);
        int order = 0;
        for (ProviderModelInput item : input) {
            ProviderModelEntity model = new ProviderModelEntity();
            model.setProviderId(providerId);
            model.setDisplayName(item.displayName().trim());
            model.setModelId(item.modelId().trim());
            model.setEnabled(item.enabled());
            model.setIsDefault(item.isDefault());
            model.setSortOrder(order++);
            models.insert(model);
        }
    }

    private List<ProviderModelEntity> listModels(Long providerId) {
        return models.selectList(new LambdaQueryWrapper<ProviderModelEntity>()
                .eq(ProviderModelEntity::getProviderId, providerId)
                .orderByAsc(ProviderModelEntity::getSortOrder));
    }

    private List<ProviderModelEntity> listModels(List<Long> providerIds) {
        if (providerIds.isEmpty()) return List.of();
        return models.selectList(new LambdaQueryWrapper<ProviderModelEntity>()
                .in(ProviderModelEntity::getProviderId, providerIds)
                .orderByAsc(ProviderModelEntity::getSortOrder));
    }

    private ProviderHealthEntity findHealth(Long providerId) {
        return health.selectOne(new LambdaQueryWrapper<ProviderHealthEntity>()
                .eq(ProviderHealthEntity::getProviderId, providerId));
    }

    private List<ProviderHealthEntity> listHealth(List<Long> providerIds) {
        if (providerIds.isEmpty()) return List.of();
        return health.selectList(new LambdaQueryWrapper<ProviderHealthEntity>()
                .in(ProviderHealthEntity::getProviderId, providerIds));
    }

    private void insertUnknownHealth(Long providerId) {
        ProviderHealthEntity entity = new ProviderHealthEntity();
        entity.setProviderId(providerId);
        entity.setStatus("UNKNOWN");
        health.insert(entity);
    }

    private void resetHealth(Long providerId) {
        ProviderHealthEntity entity = findHealth(providerId);
        if (entity == null) {
            insertUnknownHealth(providerId);
            return;
        }
        entity.setStatus("UNKNOWN");
        entity.setLatencyMs(null);
        entity.setErrorCode(null);
        entity.setMessage(null);
        entity.setCheckedAt(null);
        health.updateById(entity);
    }

    protected void persistHealth(Long providerId, ProviderAdapterRegistry.AdapterHealth tested) {
        ProviderHealthEntity entity = findHealth(providerId);
        if (entity == null) {
            entity = new ProviderHealthEntity();
            entity.setProviderId(providerId);
        }
        entity.setStatus(tested.success() ? "HEALTHY" : "UNHEALTHY");
        entity.setLatencyMs(tested.latencyMs());
        entity.setErrorCode(tested.success() ? null : tested.code());
        entity.setMessage(tested.message());
        entity.setCheckedAt(tested.checkedAt());
        if (entity.getId() == null) health.insert(entity); else health.updateById(entity);
    }

    private ProviderResponse response(ProviderEntity provider, List<ProviderModelEntity> providerModels,
                                      ProviderHealthEntity providerHealth) {
        List<ProviderModelResponse> modelResponses = providerModels.stream().map(model ->
                new ProviderModelResponse(model.getId(), model.getDisplayName(), model.getModelId(),
                        Boolean.TRUE.equals(model.getEnabled()), Boolean.TRUE.equals(model.getIsDefault()))).toList();
        ProviderHealthResponse healthResponse = providerHealth == null
                ? new ProviderHealthResponse("UNKNOWN", null, null, null, null)
                : new ProviderHealthResponse(providerHealth.getStatus(), providerHealth.getLatencyMs(),
                providerHealth.getErrorCode(), providerHealth.getMessage(), providerHealth.getCheckedAt());
        return new ProviderResponse(provider.getPublicId(), provider.getName(),
                ProviderType.valueOf(provider.getType()), provider.getBaseUrl(),
                Boolean.TRUE.equals(provider.getEnabled()), authCodec.configured(provider.getAuthConfig()),
                provider.getDefaultModelId(), modelResponses, healthResponse,
                provider.getCreatedAt(), provider.getUpdatedAt());
    }

    private record ValidatedModels(List<ProviderModelInput> models, String defaultModelId) {}
}
