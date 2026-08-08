package com.SocialService.Communities.Config;

import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.*;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class MockRedisConfig {

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = Mockito.mock(RedisTemplate.class);

        ValueOperations<String, Object> mockValueOps = Mockito.mock(ValueOperations.class);
        HashOperations<String, Object, Object> mockHashOps = Mockito.mock(HashOperations.class);
        ListOperations<String, Object> mockListOps = Mockito.mock(ListOperations.class);
        ZSetOperations<String, Object> mockZSetOps = Mockito.mock(ZSetOperations.class);

        // Bind operational dependencies
        Mockito.when(template.opsForValue()).thenReturn(mockValueOps);
        Mockito.when(template.opsForHash()).thenReturn(mockHashOps);
        Mockito.when(template.opsForList()).thenReturn(mockListOps);
        Mockito.when(template.opsForZSet()).thenReturn(mockZSetOps);

        // ✅ FIXED THE NULL POINTER EXCEPTION: Intercept the execution callbacks
        // This tells Spring Data to skip looking for physical RedisConnection sockets entirely
        Mockito.when(template.execute(ArgumentMatchers.any(RedisCallback.class))).thenReturn(null);
        Mockito.when(template.execute(ArgumentMatchers.any(SessionCallback.class))).thenReturn(null);

        // Safely map background polling actions to signal empty queue arrays
        Mockito.when(mockListOps.rightPop(ArgumentMatchers.anyString())).thenReturn(null);

        // Build stable profile dictionaries to satisfy user checking methods
        Map<Object, Object> mockProfileMap = new HashMap<>();
        mockProfileMap.put("username", "JohnTheShield");
        mockProfileMap.put("avatarUrl", "/v1/vault/stream/default.png");
        mockProfileMap.put("isPremium", "true");
        mockProfileMap.put("isVerifiedResident", "true");
        Mockito.when(mockHashOps.entries(ArgumentMatchers.anyString())).thenReturn(mockProfileMap);

        return template;
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return Mockito.mock(RedisConnectionFactory.class);
    }
}