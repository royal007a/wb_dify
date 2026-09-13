package com.hify.provider.api;

import com.hify.common.PageResult;

public interface ProviderService {
    String create(ProviderCreateRequest request);
    ProviderResponse get(String publicId);
    PageResult<ProviderResponse> list(Integer page, Integer pageSize, String keyword,
                                      ProviderType type, Boolean enabled);
    void update(String publicId, ProviderUpdateRequest request);
    void delete(String publicId);
    ConnectionTestResponse testConnection(String publicId);
}
