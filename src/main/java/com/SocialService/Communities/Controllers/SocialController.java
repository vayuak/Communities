package com.SocialService.Communities.Controllers;

import com.SocialService.Communities.Clients.UserCatalogClient;
import com.SocialService.Communities.Models.Post;
import com.SocialService.Communities.Repositories.PostRepository;
import com.SocialService.Communities.Repositories.RadarContinentRepository;
import com.SocialService.Communities.Repositories.SocialService;
import com.SocialService.Communities.Services.ProfileCacheService;
import com.SocialService.Communities.Clients.BlobClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/social")
@RequiredArgsConstructor
@Slf4j
public class SocialController {

    private final SocialService socialService;
    private final com.SocialService.Communities.Services.NotificationRegistryService notificationRegistryService;
    private final PostRepository postRepository;
    private final JdbcTemplate jdbcTemplate;
    @Autowired
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserCatalogClient userCatalogClient;
    private final RadarContinentRepository radarContinentRepository;
    private final BlobClient blobClient;
    private final ProfileCacheService profileCacheService;

    // 🟢 SELF-HEALING: Redis-First Avatar Resolver with Negative Caching
    private String resolveAndCacheAvatar(String username) {
        if (username == null || username.isBlank()) return null;
        String cleanName = username.trim().toLowerCase();
        String redisKey = "user:avatar:" + cleanName;

        Object cachedAvatar = redisTemplate.opsForValue().get(redisKey);
        if (cachedAvatar != null) {
            return cachedAvatar.equals("NO_AVATAR") ? null : (String) cachedAvatar;
        }

        try {
            List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(cleanName);
            if (remoteUser != null && !remoteUser.isEmpty() && remoteUser.get(0).get("profilePictureUrl") != null) {
                String fetchedAvatar = (String) remoteUser.get(0).get("profilePictureUrl");
                redisTemplate.opsForValue().set(redisKey, fetchedAvatar, java.time.Duration.ofHours(24));
                return fetchedAvatar;
            }
        } catch (Exception ignored) {}

        redisTemplate.opsForValue().set(redisKey, "NO_AVATAR", java.time.Duration.ofHours(1));
        return null;
    }

    // 🟢 SELF-HEALING: Redis-First ID-to-Username Resolver
    private String resolveAndCacheUsername(Long userId) {
        String redisKey = "user:id_to_name:" + userId;

        Object cachedName = redisTemplate.opsForValue().get(redisKey);
        if (cachedName != null) return (String) cachedName;

        try {
            String uname = jdbcTemplate.queryForObject("SELECT username FROM posts WHERE user_id = ? LIMIT 1", String.class, userId);
            if (uname != null && !uname.trim().isEmpty()) {
                redisTemplate.opsForValue().set(redisKey, uname, java.time.Duration.ofDays(30));
                return uname;
            }
        } catch (Exception ignored) {}

        String fallback = "user" + userId;
        redisTemplate.opsForValue().set(redisKey, fallback, java.time.Duration.ofHours(1));
        return fallback;
    }

    @GetMapping(value = "/notifications/subscribe", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToNotificationStream(@RequestAttribute("userId") Long userId) {
        return notificationRegistryService.registerClient(userId);
    }

    @PostMapping("/post/create")
    public ResponseEntity<?> createPost(@RequestBody Post post, @RequestAttribute("userId") Long userId, @RequestAttribute("username") String username) {
        try {
            // 🟢 FORCE CACHE: Map ID to Username instantly on post creation
            redisTemplate.opsForValue().set("user:id_to_name:" + userId, username.trim().toLowerCase(), java.time.Duration.ofDays(30));
            return ResponseEntity.status(HttpStatus.CREATED).body(socialService.createPost(post, userId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchGlobalScamDatabase(@RequestParam String keyword, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size, @RequestAttribute("userId") Long userId) {
        String input = keyword.trim();
        Map<String, Object> targetPayload = new HashMap<>();

        if (input.startsWith("@") && input.length() > 1) {
            String targetHandle = input.substring(1).trim().toLowerCase();
            List<Map<String, Object>> remoteUsers = userCatalogClient.searchUsersByHandle(targetHandle);

            if (remoteUsers != null && !remoteUsers.isEmpty() && remoteUsers.get(0).get("profilePictureUrl") != null) {
                redisTemplate.opsForValue().set("user:avatar:" + targetHandle, remoteUsers.get(0).get("profilePictureUrl"), java.time.Duration.ofHours(24));
            }

            targetPayload.put("type", "USERS");
            targetPayload.put("results", remoteUsers);
            return ResponseEntity.ok(targetPayload);
        }

        String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", user_id AS \"userId\", username FROM posts WHERE LOWER(content) LIKE LOWER(?) OR LOWER(title) LIKE LOWER(?) ORDER BY created_at DESC LIMIT ? OFFSET ?";
        String searchParam = "%" + input + "%";
        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, searchParam, searchParam, size, page * size);

        Set<String> uniqueUsernames = livePosts.stream().map(p -> (String) p.get("username")).collect(Collectors.toSet());
        Map<String, String> avatarMap = new HashMap<>();
        for (String uname : uniqueUsernames) {
            avatarMap.put(uname, resolveAndCacheAvatar(uname));
        }
        livePosts.forEach(post -> post.put("avatarUrl", avatarMap.get((String) post.get("username"))));

        targetPayload.put("type", "POSTS");
        targetPayload.put("results", livePosts);
        return ResponseEntity.ok(targetPayload);
    }

    @PutMapping("/user/profile/update-direct")
    public ResponseEntity<?> updateProfileDataDirectly(@RequestAttribute("userId") Long userId, @RequestAttribute("username") String username, @RequestBody Map<String, String> body) {
        try {
            redisTemplate.opsForValue().set("user:id_to_name:" + userId, username.trim().toLowerCase(), java.time.Duration.ofDays(30));
            String newPic = body.get("profilePictureUrl");
            if (newPic != null) redisTemplate.opsForValue().set("user:avatar:" + username.trim().toLowerCase(), newPic, java.time.Duration.ofHours(24));
            return ResponseEntity.ok(Map.of("status", "SUCCESS"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/meta/tier-one-cities")
    public ResponseEntity<?> getTierOneGlobalMatrix() {
        return ResponseEntity.ok(radarContinentRepository.findAll().stream().map(record -> {
            Map<String, Object> map = new HashMap<>();
            map.put("continent", record.getName());
            map.put("cities", record.getCities().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
            return map;
        }).toList());
    }

    @PostMapping("/post/{postId}/vote")
    public ResponseEntity<?> voteOnPost(@PathVariable Long postId, @RequestParam String direction, @RequestAttribute("userId") Long userId) {
        try {
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", socialService.votePost(postId, userId, direction)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/post/{postId}/share")
    public ResponseEntity<?> sharePost(@PathVariable Long postId) {
        try {
            socialService.incrementShareCount(postId);
            return ResponseEntity.ok(Map.of("status", "SUCCESS"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/post/{postId}/comment")
    public ResponseEntity<?> addSecureComment(
            @PathVariable Long postId,
            @Valid @RequestBody com.SocialService.Communities.DTOs.CommentRequestDTO request,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username) { // 🟢 INJECTED USERNAME
        try {
            // 🟢 FORCE CACHE: The exact moment someone comments, their ID is permanently mapped to their clean username!
            redisTemplate.opsForValue().set("user:id_to_name:" + userId, username.trim().toLowerCase(), java.time.Duration.ofDays(30));

            com.SocialService.Communities.Models.Comment savedComment =
                    socialService.addSecureComment(postId, userId, request.getContent(), request.getParentId());
            return ResponseEntity.status(HttpStatus.CREATED).body(savedComment);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/post/{postId}/comments")
    public ResponseEntity<List<com.SocialService.Communities.DTOs.CommentResponseDTO>> getComments(@PathVariable Long postId) {
        String sql = "SELECT id, content, parent_id, created_at, user_id FROM comments WHERE post_id = ? ORDER BY created_at ASC";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, postId);

            Set<Long> uniqueUserIds = rows.stream()
                    .map(r -> ((Number) r.get("user_id")).longValue())
                    .collect(Collectors.toSet());

            Map<Long, String> userIdToUsernameMap = new HashMap<>();
            Map<String, String> avatarMap = new HashMap<>();

            for (Long uid : uniqueUserIds) {
                String uname = resolveAndCacheUsername(uid);
                userIdToUsernameMap.put(uid, uname);
                avatarMap.put(uname, resolveAndCacheAvatar(uname));
            }

            List<com.SocialService.Communities.DTOs.CommentResponseDTO> allComments = new java.util.ArrayList<>();
            Map<Long, List<com.SocialService.Communities.DTOs.CommentResponseDTO>> childrenMap = new java.util.HashMap<>();
            List<com.SocialService.Communities.DTOs.CommentResponseDTO> rootComments = new java.util.ArrayList<>();

            for (Map<String, Object> row : rows) {
                Long commentUserId = ((Number) row.get("user_id")).longValue();
                String resolvedUsername = userIdToUsernameMap.get(commentUserId);
                Object parentObj = row.get("parent_id");
                Object createdObj = row.get("created_at");

                com.SocialService.Communities.DTOs.CommentResponseDTO dto = com.SocialService.Communities.DTOs.CommentResponseDTO.builder()
                        .id(((Number) row.get("id")).longValue())
                        .parentId(parentObj != null ? ((Number) parentObj).longValue() : null)
                        .content((String) row.get("content"))
                        .username(resolvedUsername)
                        .avatarUrl(avatarMap.get(resolvedUsername))
                        .createdAt(createdObj != null ? ((java.sql.Timestamp) createdObj).toLocalDateTime() : null)
                        .build();
                allComments.add(dto);

                if (dto.getParentId() == null) rootComments.add(dto);
                else childrenMap.computeIfAbsent(dto.getParentId(), k -> new java.util.ArrayList<>()).add(dto);
            }

            List<com.SocialService.Communities.DTOs.CommentResponseDTO> sortedNestingList = new java.util.ArrayList<>();
            java.util.Stack<com.SocialService.Communities.DTOs.CommentResponseDTO> stack = new java.util.Stack<>();

            for (int i = rootComments.size() - 1; i >= 0; i--) stack.push(rootComments.get(i));

            while (!stack.isEmpty()) {
                com.SocialService.Communities.DTOs.CommentResponseDTO current = stack.pop();
                sortedNestingList.add(current);
                List<com.SocialService.Communities.DTOs.CommentResponseDTO> children = childrenMap.get(current.getId());
                if (children != null) {
                    for (int i = children.size() - 1; i >= 0; i--) stack.push(children.get(i));
                }
            }
            return ResponseEntity.ok(sortedNestingList);
        } catch (Exception e) {
            log.error("Failed to fetch comments: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping({
            "/user/{username}/full-profile",
            "/user/{username}/full-profile/"
    })
    public ResponseEntity<?> getFullProfile(@PathVariable String username) {
        Map<String, Object> response = new HashMap<>();

        String tempDecoded;
        try {
            tempDecoded = java.net.URLDecoder.decode(username, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            tempDecoded = username;
        }
        final String cleanUsername = tempDecoded.replace("@", "").trim().toLowerCase();

        try {
            Map<String, Object> safeProfile = new HashMap<>();

            String liveAvatarUrl = resolveAndCacheAvatar(cleanUsername);

            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(cleanUsername);
                if (remoteUser != null && !remoteUser.isEmpty()) {
                    safeProfile = new HashMap<>(remoteUser.get(0));
                }
            } catch (Exception e) {
                log.warn("Feign user catalog lookup failed for {}: {}", cleanUsername, e.getMessage());
            }

            if (safeProfile.isEmpty()) {
                safeProfile.put("username", cleanUsername);
            }

            safeProfile.put("avatarUrl", liveAvatarUrl);
            safeProfile.put("profilePictureUrl", liveAvatarUrl);
            response.put("profile", safeProfile);

            String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", " +
                    "score, comment_count AS \"commentCount\", created_at AS \"createdAt\", city_name AS \"cityName\" " +
                    "FROM posts WHERE LOWER(username) = LOWER(?) ORDER BY created_at DESC";

            List<Map<String, Object>> userPosts = jdbcTemplate.queryForList(sql, cleanUsername);

            userPosts.forEach(post -> {
                post.put("avatarUrl", liveAvatarUrl);
                post.put("username", cleanUsername);
            });

            response.put("posts", userPosts);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to aggregate profile: " + e.getMessage()));
        }
    }

    @GetMapping("/feed")
    public ResponseEntity<List<Map<String, Object>>> getCityFeed(@RequestParam String city, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", user_id AS \"userId\", username FROM posts WHERE LOWER(city_name) = LOWER(?) ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, city.trim(), size, page * size);
        Set<String> uniqueUsernames = livePosts.stream().map(p -> (String) p.get("username")).collect(Collectors.toSet());
        Map<String, String> avatarMap = new HashMap<>();

        for (String uname : uniqueUsernames) {
            avatarMap.put(uname, resolveAndCacheAvatar(uname));
        }

        livePosts.forEach(post -> post.put("avatarUrl", avatarMap.get((String) post.get("username"))));
        return ResponseEntity.ok(livePosts);
    }

    @GetMapping("/post/my-posts")
    public ResponseEntity<List<Map<String, Object>>> getMyPosts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestAttribute("userId") Long userId) {
        String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", user_id AS \"userId\", username FROM posts WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, userId, size, page * size);
        if (!livePosts.isEmpty()) {
            String liveAvatar = resolveAndCacheAvatar((String) livePosts.get(0).get("username"));
            livePosts.forEach(post -> post.put("avatarUrl", liveAvatar));
        }
        return ResponseEntity.ok(livePosts);
    }

    @DeleteMapping("/post/comment/{commentId}")
    public ResponseEntity<?> deleteComment(@PathVariable Long commentId, @RequestAttribute("userId") Long userId) {
        try {
            Map<String, Object> commentData = jdbcTemplate.queryForMap("SELECT user_id, post_id FROM comments WHERE id = ?", commentId);
            if (((Number) commentData.get("user_id")).longValue() != userId) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized"));
            Long postId = ((Number) commentData.get("post_id")).longValue();
            jdbcTemplate.update("UPDATE comments SET parent_id = NULL WHERE parent_id = ?", commentId);
            jdbcTemplate.update("DELETE FROM comments WHERE id = ?", commentId);
            jdbcTemplate.update("UPDATE posts SET comment_count = GREATEST(COALESCE(comment_count, 0) - 1, 0) WHERE id = ?", postId);
            return ResponseEntity.ok(Map.of("status", "SUCCESS"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/user/profile/upload-and-update", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndUpdateProfile(@RequestAttribute("userId") Long userId, @RequestAttribute("username") String username, @RequestParam("file") MultipartFile file) {
        try {
            // 🟢 FORCE CACHE: Profile update guarantees we remember them
            redisTemplate.opsForValue().set("user:id_to_name:" + userId, username.trim().toLowerCase(), java.time.Duration.ofDays(30));

            Map<String, Object> blobResponse = blobClient.uploadMedia(file, String.valueOf(userId));
            String newPicUrl = (String) blobResponse.get("mediaUrl");
            if (newPicUrl == null || newPicUrl.isEmpty()) throw new IllegalStateException("Empty URL");
            userCatalogClient.updateInternalAvatar(username.trim().toLowerCase(), Map.of("profilePictureUrl", newPicUrl));

            redisTemplate.opsForValue().set("user:avatar:" + username.trim().toLowerCase(), newPicUrl, java.time.Duration.ofHours(24));

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "avatarUrl", newPicUrl));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/post/upload-and-create", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndCreatePost(@RequestAttribute("userId") Long userId, @RequestAttribute("username") String username, @RequestParam("file") MultipartFile file, @RequestParam("title") String title, @RequestParam("content") String content, @RequestParam("cityName") String cityName, @RequestParam("country") String country) {
        try {
            // 🟢 FORCE CACHE
            redisTemplate.opsForValue().set("user:id_to_name:" + userId, username.trim().toLowerCase(), java.time.Duration.ofDays(30));

            Map<String, Object> blobResponse = blobClient.uploadMedia(file, String.valueOf(userId));
            Post post = new Post();
            post.setTitle(title);
            post.setContent(content);
            post.setCityName(cityName);
            post.setCity(cityName);
            post.setCountry(country);
            post.setMediaUrl((String) blobResponse.get("mediaUrl"));
            post.setMediaType((String) blobResponse.get("mediaType"));
            post.setUsername(username);
            return ResponseEntity.status(HttpStatus.CREATED).body(socialService.createPost(post, userId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/post/{postId}/delete")
    public ResponseEntity<?> purgePostRecord(@PathVariable Long postId, @RequestAttribute("userId") Long userId) {
        try {
            Map<String, Object> postData = jdbcTemplate.queryForMap("SELECT user_id, media_url FROM posts WHERE id = ?", postId);
            if (((Number) postData.get("user_id")).longValue() != userId) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized"));
            if (postData.get("media_url") != null && postData.get("media_url").toString().contains("/stream/")) {
                String mediaUrl = postData.get("media_url").toString();
                try { blobClient.deleteMedia(mediaUrl.substring(mediaUrl.lastIndexOf("/") + 1)); } catch (Exception ignored) {}
            }
            jdbcTemplate.update("UPDATE comments SET parent_id = NULL WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM comments WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM post_upvotes WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM post_downvotes WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM posts WHERE id = ?", postId);
            return ResponseEntity.ok(Map.of("status", "SUCCESS"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/post/{postId}")
    public ResponseEntity<?> getSinglePost(@PathVariable Long postId) {
        try {
            Map<String, Object> post = jdbcTemplate.queryForMap("SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", user_id AS \"userId\", username FROM posts WHERE id = ?", postId);
            post.put("avatarUrl", resolveAndCacheAvatar((String) post.get("username")));
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Post not found."));
        }
    }

    @GetMapping("/user/{username}/profile")
    public ResponseEntity<?> getUserProfileData(@PathVariable String username) {
        try {
            String cleanName = username.trim().toLowerCase();
            List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(cleanName);
            if (remoteUser != null && !remoteUser.isEmpty()) {
                Map<String, Object> safeProfile = new HashMap<>(remoteUser.get(0));

                if (safeProfile.get("profilePictureUrl") != null) {
                    redisTemplate.opsForValue().set("user:avatar:" + cleanName, safeProfile.get("profilePictureUrl"), java.time.Duration.ofHours(24));
                }

                safeProfile.put("avatarUrl", safeProfile.get("profilePictureUrl"));
                return ResponseEntity.ok(safeProfile);
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
    @PostMapping("/moderation/report")
    public ResponseEntity<?> handleModerationReport(
            @RequestBody Map<String, Object> payload,
            @RequestAttribute("userId") Long reporterId,
            @RequestAttribute("username") String reporterUsername,
            @RequestHeader(value = "X-User-City", required = false) String reporterCity) {
        try {
            Long targetPostId = null;
            if (payload.containsKey("targetId") && payload.get("targetId") != null) {
                targetPostId = Long.valueOf(payload.get("targetId").toString());
            } else if (payload.containsKey("targetPostId") && payload.get("targetPostId") != null) {
                targetPostId = Long.valueOf(payload.get("targetPostId").toString());
            }

            if (targetPostId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Target ID missing."));
            }

            String reason = (String) payload.getOrDefault("reason", "Inappropriate Content");

            socialService.submitReport(reporterId, reporterUsername, targetPostId, reason, reporterCity);

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Report logged for review."));
        } catch (Exception e) {
            log.error("Report processing error: {}", e.getMessage());
            // Always return 200 to trick weaponized bot campaigns into thinking their report went through
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Report logged for review."));
        }
    }
}