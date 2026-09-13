package com.hify.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    @ConditionalOnProperty(name = "hify.redis.enabled", havingValue = "true")
    CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .computePrefixWith(cacheName -> "hify:" + cacheName + ":")
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()));
        Map<String, RedisCacheConfiguration> cacheTtls = Map.of(
                "provider-cache", defaults.entryTtl(Duration.ofMinutes(30)),
                "agent-cache", defaults.entryTtl(Duration.ofMinutes(30)),
                "session-cache", defaults.entryTtl(Duration.ofHours(2))
        );
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(cacheTtls)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "hify.redis.enabled", havingValue = "false", matchIfMissing = true)
    CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }
}

