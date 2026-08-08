package com.SocialService.Communities.Services;

import java.util.Map;

public interface ProfileCacheService {
    Map<String, String> getUserProfileSummary(Long userId);
    void cacheUserProfile(Long userId, String username, String avatarUrl);
    void upgradeUserToPremiumTier(Long userId);
    void elevateUserToVerifiedResident(Long userId);
}