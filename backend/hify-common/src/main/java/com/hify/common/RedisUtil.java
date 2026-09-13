package com.hify.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "hify.redis.enabled", havingValue = "true")
public class RedisUtil {
    private final RedisTemplate<String, Object> redis;

    public RedisUtil(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    public Object get(String key) { return redis.opsForValue().get(key); }
    public void set(String key, Object value) { redis.opsForValue().set(key, value); }
    public void set(String key, Object value, Duration ttl) { redis.opsForValue().set(key, value, ttl); }
    public Boolean delete(String key) { return redis.delete(key); }
    public Boolean expire(String key, Duration ttl) { return redis.expire(key, ttl); }
}
