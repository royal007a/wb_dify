package com.hify.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.provider.entity.ProviderModelEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProviderModelMapper extends BaseMapper<ProviderModelEntity> {
    @Delete("DELETE FROM provider_models WHERE provider_id = #{providerId}")
    int physicallyDeleteByProviderId(@Param("providerId") Long providerId);
}
