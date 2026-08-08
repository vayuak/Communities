package com.SocialService.Communities.Services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisProfileCacheServiceImpl implements ProfileCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String PROFILE_KEY_PREFIX = "user:profile:";

    @Override
    public Map<String, String> getUserProfileSummary(Long userId) {
        String key = PROFILE_KEY_PREFIX + userId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        Map<String, String> summary = new HashMap<>();
        summary.put("username", entries.containsKey("username") ? (String) entries.get("username") : "AnonymousTraveler");
        summary.put("avatarUrl", entries.containsKey("avatarUrl") ? (String) entries.get("avatarUrl") : "/v1/vault/stream/default.png");
        summary.put("isPremium", entries.containsKey("isPremium") ? (String) entries.get("isPremium") : "false");
        summary.put("isVerifiedResident", entries.containsKey("isVerifiedResident") ? (String) entries.get("isVerifiedResident") : "false");

        return summary;
    }

    @Override
    public void cacheUserProfile(Long userId, String username, String avatarUrl) {
        String key = PROFILE_KEY_PREFIX + userId;
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("username", username);
            fields.put("avatarUrl", avatarUrl);

            redisTemplate.opsForHash().putAll(key, fields);
            log.info("⚡ REDIS RAM SYNC: User {} details written to remote Redis hardware.", userId);
        } catch (Exception e) {
            log.error("Failed to sync profile properties to Redis: ", e);
        }
    }

    @Override
    public void upgradeUserToPremiumTier(Long userId) {
        String key = PROFILE_KEY_PREFIX + userId;
        try {
            redisTemplate.opsForHash().put(key, "isPremium", "true");
            log.info("💎 REDIS MATRIX SYNC: User {} marked as cloud PREMIUM tier.", userId);
        } catch (Exception e) {
            log.error("Failed to commit premium state modifications: ", e);
            throw new RuntimeException("Cloud cache failure.");
        }
    }

    @Override
    public void elevateUserToVerifiedResident(Long userId) {
        String key = PROFILE_KEY_PREFIX + userId;
        try {
            redisTemplate.opsForHash().put(key, "isVerifiedResident", "true");
            log.info("👑 REDIS IDENTITY ELEVATION: User profile {} flagged as a trusted resident.", userId);
        } catch (Exception e) {
            log.error("Failed to commit trust network modifications: ", e);
            throw new RuntimeException("Cloud cache failure.");
        }
    }
}