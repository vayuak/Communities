package com.SocialService.Communities.Services;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Profile({"default", "dev", "test"}) // Runs automatically on your local PC
public class LocalProfileCacheServiceImpl implements ProfileCacheService {

    private final Map<String, Map<String, String>> mockProfileCacheRing = new ConcurrentHashMap<>();
    private static final String PROFILE_KEY_PREFIX = "user:profile:";

    @Override
    public Map<String, String> getUserProfileSummary(Long userId) {
        String key = PROFILE_KEY_PREFIX + userId;
        Map<String, String> entries = mockProfileCacheRing.getOrDefault(key, new HashMap<>());

        Map<String, String> summary = new HashMap<>();
        summary.put("username", entries.getOrDefault("username", "AnonymousTraveler"));
        summary.put("avatarUrl", entries.getOrDefault("avatarUrl", "/v1/vault/stream/default.png"));
        summary.put("isPremium", entries.getOrDefault("isPremium", "false"));
        summary.put("isVerifiedResident", entries.getOrDefault("isVerifiedResident", "false"));
        return summary;
    }

    @Override
    public void cacheUserProfile(Long userId, String username, String avatarUrl) {
        String key = PROFILE_KEY_PREFIX + userId;
        Map<String, String> fields = mockProfileCacheRing.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        fields.put("username", username);
        fields.put("avatarUrl", avatarUrl);
    }

    @Override
    public void upgradeUserToPremiumTier(Long userId) {
        String key = PROFILE_KEY_PREFIX + userId;
        mockProfileCacheRing.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put("isPremium", "true");
    }

    @Override
    public void elevateUserToVerifiedResident(Long userId) {
        String key = PROFILE_KEY_PREFIX + userId;
        mockProfileCacheRing.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put("isVerifiedResident", "true");
    }
}