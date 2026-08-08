package com.SocialService.Communities.Config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@Profile("prod") // 🟢 Instantly turns on when deployed to cloud production systems
@RequiredArgsConstructor
public class RedisCacheEngineImpl implements CacheEngine {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void addToZSet(String key, String value, double score) {
        redisTemplate.opsForZSet().add(key, value, score);
    }

    @Override
    public Set<Object> reverseRangeZSet(String key, long start, long end) {
        return Optional.ofNullable(redisTemplate.opsForZSet().reverseRange(key, start, end)).orElse(Collections.emptySet());
    }

    @Override
    public long getZSetSize(String key) {
        return Optional.ofNullable(redisTemplate.opsForZSet().zCard(key)).orElse(0L);
    }

    @Override
    public void setValue(String key, Object value, long timeoutHours) {
        redisTemplate.opsForValue().set(key, value, timeoutHours, TimeUnit.HOURS);
    }

    @Override
    public Object getValue(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public void putInHash(String key, String hashKey, Object value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    @Override
    public Object getFromHash(String key, String hashKey) {
        return redisTemplate.opsForHash().get(key, hashKey);
    }

    @Override
    public void incrementHash(String key, String hashKey, long delta) {
        redisTemplate.opsForHash().increment(key, hashKey, delta);
    }

    @Override
    public void leftPushList(String key, Object value) {
        redisTemplate.opsForList().leftPush(key, value);
    }

    @Override
    public List<Object> rangeList(String key, long start, long end) {
        return Optional.ofNullable(redisTemplate.opsForList().range(key, start, end)).orElse(Collections.emptyList());
    }

    @Override
    public void trimList(String key, long start, long end) {
        redisTemplate.opsForList().trim(key, start, end);
    }

    @Override
    public void deleteKey(String key) {
        redisTemplate.delete(key);
    }
}