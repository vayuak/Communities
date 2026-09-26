package com.SocialService.Communities.Controllers;

import com.SocialService.Communities.Clients.UserCatalogClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves the "Missing DPs everywhere" bug.
 * Intercepts frontend batch profile requests, checks Redis cache,
 * and fetches missing avatars from UserCatalog efficiently.
 */
@RestController
@RequestMapping("/api/social")
@RequiredArgsConstructor
@Slf4j
public class ProfileBatchController {

    private static final int MAX_BATCH = 100;
    private static final String CACHE_PREFIX = "dp:v1:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String NULL_MARKER = "\u0000none";

    private final UserCatalogClient userCatalogClient;
    private final StringRedisTemplate redisTemplate;

    public record ProfileLite(String username, String avatarUrl) {}

    @PostMapping("/user/profiles/batch")
    public ResponseEntity<?> batchProfiles(@RequestBody Map<String, List<String>> body) {
        List<String> requested = body.get("usernames");

        if (requested == null || requested.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "usernames is required"));
        }
        if (requested.size() > MAX_BATCH) {
            return ResponseEntity.badRequest().body(Map.of("error", "TOO_MANY", "max", MAX_BATCH));
        }

        Set<String> handles = requested.stream()
                .filter(Objects::nonNull)
                .map(s -> s.replace("@", "").trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<ProfileLite> out = new ArrayList<>();
        List<String> misses = new ArrayList<>();

        for (String handle : handles) {
            String cached = safeGet(CACHE_PREFIX + handle);
            if (cached == null) {
                misses.add(handle);
            } else {
                out.add(new ProfileLite(handle, NULL_MARKER.equals(cached) ? null : cached));
            }
        }

        for (String handle : misses) {
            String avatar = null;
            try {
                List<Map<String, Object>> found = userCatalogClient.searchUsersByHandle(handle);
                if (found != null && !found.isEmpty()) {
                    Object url = found.get(0).get("profilePictureUrl");
                    if (url == null) url = found.get(0).get("avatarUrl");
                    if (url != null && !url.toString().isBlank()) avatar = url.toString();
                }
            } catch (Exception e) {
                log.debug("Avatar lookup failed for {}: {}", handle, e.getMessage());
            }

            safeSet(CACHE_PREFIX + handle, avatar == null ? NULL_MARKER : avatar);
            out.add(new ProfileLite(handle, avatar));
        }

        return ResponseEntity.ok(Map.of("profiles", out));
    }

    @GetMapping("/user/{username}/avatar")
    public ResponseEntity<?> singleAvatar(@PathVariable String username) {
        String handle = username.replace("@", "").trim().toLowerCase();
        if (handle.isEmpty()) return ResponseEntity.badRequest().build();

        String cached = safeGet(CACHE_PREFIX + handle);
        if (cached != null) {
            return ResponseEntity.ok(new ProfileLite(handle, NULL_MARKER.equals(cached) ? null : cached));
        }

        String avatar = null;
        try {
            List<Map<String, Object>> found = userCatalogClient.searchUsersByHandle(handle);
            if (found != null && !found.isEmpty()) {
                Object url = found.get(0).get("profilePictureUrl");
                if (url == null) url = found.get(0).get("avatarUrl");
                if (url != null && !url.toString().isBlank()) avatar = url.toString();
            }
        } catch (Exception e) {
            log.debug("Avatar lookup failed for {}: {}", handle, e.getMessage());
        }

        safeSet(CACHE_PREFIX + handle, avatar == null ? NULL_MARKER : avatar);
        return ResponseEntity.ok(new ProfileLite(handle, avatar));
    }

    private String safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private void safeSet(String key, String value) {
        try {
            redisTemplate.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception ignored) { }
    }
}