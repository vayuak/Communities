package com.SocialService.Communities.Controllers;

import com.SocialService.Communities.Clients.UserCatalogClient;
import com.SocialService.Communities.Models.Post;
import com.SocialService.Communities.Repositories.PostRepository;
import com.SocialService.Communities.Repositories.RadarContinentRepository;
import com.SocialService.Communities.Repositories.SocialService;
import com.SocialService.Communities.Services.ProfileCacheService;
import com.SocialService.Communities.Clients.BlobClient;
import com.SocialService.Communities.Utilities.PromoFilter;
import com.SocialService.Communities.Utilities.SafeUsers;
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

import java.security.Principal;
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

    private String resolveAndCacheAvatar(String username) {
        if (username == null || username.isBlank()) return null;
        String cleanName = username.trim().toLowerCase();
        String redisKey = "user:avatar:" + cleanName;

        try {
            Object cachedAvatar = redisTemplate.opsForValue().get(redisKey);
            if (cachedAvatar != null) {
                return cachedAvatar.equals("NO_AVATAR") ? null : (String) cachedAvatar;
            }
        } catch (Exception ignored) {}

        try {
            List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(cleanName);
            Map<String, Object> exactUser = SafeUsers.exact(remoteUser, cleanName);
            if (exactUser != null && exactUser.get("profilePictureUrl") != null) {
                String fetchedAvatar = (String) exactUser.get("profilePictureUrl");
                redisTemplate.opsForValue().set(redisKey, fetchedAvatar, java.time.Duration.ofHours(24));
                return fetchedAvatar;
            }
        } catch (Exception ignored) {}

        try {
            redisTemplate.opsForValue().set(redisKey, "NO_AVATAR", java.time.Duration.ofHours(1));
        } catch (Exception ignored) {}

        return null;
    }

    private String resolveAndCacheUsername(Long userId) {
        String redisKey = "user:id_to_name:" + userId;
        try {
            Object cachedName = redisTemplate.opsForValue().get(redisKey);
            if (cachedName != null) return (String) cachedName;
        } catch (Exception ignored) {}

        try {
            String uname = jdbcTemplate.queryForObject("SELECT username FROM posts WHERE user_id = ? LIMIT 1", String.class, userId);
            if (uname != null && !uname.trim().isEmpty()) {
                redisTemplate.opsForValue().set(redisKey, uname, java.time.Duration.ofDays(30));
                return uname;
            }
        } catch (Exception ignored) {}

        String fallback = "user" + userId;
        try {
            redisTemplate.opsForValue().set(redisKey, fallback, java.time.Duration.ofHours(1));
        } catch (Exception ignored) {}
        return fallback;
    }

    @GetMapping(value = "/notifications/subscribe", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToNotificationStream(@RequestAttribute("userId") Long userId) {
        return notificationRegistryService.registerClient(userId);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchGlobalScamDatabase(@RequestParam String keyword, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size, @RequestAttribute("userId") Long userId) {
        try {
            String input = keyword.trim();
            Map<String, Object> targetPayload = new HashMap<>();

            // PATHWAY A: User Search
            if (input.startsWith("@") && input.length() > 1) {
                String targetHandle = input.substring(1).trim().toLowerCase();
                try {
                    List<Map<String, Object>> remoteUsers = userCatalogClient.searchUsersByHandle(targetHandle);
                    targetPayload.put("type", "USERS");
                    targetPayload.put("results", SafeUsers.sanitize(remoteUsers));
                } catch (Exception e) {
                    targetPayload.put("type", "USERS");
                    targetPayload.put("results", new ArrayList<>()); // Graceful degradation on Feign crash
                }
                return ResponseEntity.ok(targetPayload);
            }

            // PATHWAY B: Post Search
            size = Math.min(Math.max(size, 1), 50); page = Math.max(page, 0);
            String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", user_id AS \"userId\", username FROM posts WHERE (LOWER(content) LIKE LOWER(?) OR LOWER(title) LIKE LOWER(?))" + NOT_FLAGGED + "ORDER BY created_at DESC LIMIT ? OFFSET ?";
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
        } catch (Exception e) {
            log.error("Search failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
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
        try {
            List<Map<String, Object>> response = radarContinentRepository.findAll().stream().map(record -> {
                Map<String, Object> map = new HashMap<>();
                map.put("continent", record.getName());
                map.put("cities", record.getCities() != null ? record.getCities().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList() : new ArrayList<>());
                return map;
            }).toList();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Cities fetch failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to load location matrix."));
        }
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
            @RequestAttribute("username") String username) {
        try {
            String promo = PromoFilter.check(request.getContent());
            if (promo != null) return ResponseEntity.badRequest().body(Map.of("error", promo));
            if (!allow("rl:comment:" + userId, 60, java.time.Duration.ofHours(1))) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "You are commenting too fast. Please wait a bit."));
            }
            redisTemplate.opsForValue().set("user:id_to_name:" + userId, username.trim().toLowerCase(), java.time.Duration.ofDays(30));
            com.SocialService.Communities.Models.Comment savedComment =
                    socialService.addSecureComment(postId, userId, request.getContent(), request.getParentId());
            return ResponseEntity.status(HttpStatus.CREATED).body(savedComment);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/post/{postId}/comments")
    public ResponseEntity<?> getComments(@PathVariable Long postId) {
        try {
            String sql = "SELECT id, content, parent_id, created_at, user_id FROM comments WHERE post_id = ? ORDER BY created_at ASC";
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
            log.error("Failed to fetch comments", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping({ "/user/{username}/full-profile", "/user/{username}/full-profile/" })
    public ResponseEntity<?> getFullProfile(@PathVariable String username) {
        try {
            Map<String, Object> response = new HashMap<>();
            String tempDecoded;
            try { tempDecoded = java.net.URLDecoder.decode(username, java.nio.charset.StandardCharsets.UTF_8); }
            catch (Exception e) { tempDecoded = username; }

            final String cleanUsername = tempDecoded.replace("@", "").trim().toLowerCase();
            Map<String, Object> safeProfile = new HashMap<>();
            String liveAvatarUrl = resolveAndCacheAvatar(cleanUsername);

            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(cleanUsername);
                Map<String, Object> exactUser = SafeUsers.exact(remoteUser, cleanUsername);
                if (exactUser != null) {
                    safeProfile = new HashMap<>(exactUser);
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

            String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", city_name AS \"cityName\" FROM posts WHERE LOWER(username) = LOWER(?) ORDER BY created_at DESC";
            List<Map<String, Object>> userPosts = jdbcTemplate.queryForList(sql, cleanUsername);
            userPosts.forEach(post -> {
                post.put("avatarUrl", liveAvatarUrl);
                post.put("username", cleanUsername);
            });
            response.put("posts", userPosts);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Full profile fetch failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/feed")
    public ResponseEntity<?> getCityFeed(@RequestParam String city, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        try {
            size = Math.min(Math.max(size, 1), 50); page = Math.max(page, 0);
            String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", user_id AS \"userId\", username FROM posts WHERE LOWER(city_name) = LOWER(?)" + NOT_FLAGGED + "ORDER BY created_at DESC LIMIT ? OFFSET ?";
            List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, city.trim(), size, page * size);

            Set<String> uniqueUsernames = livePosts.stream().map(p -> (String) p.get("username")).collect(Collectors.toSet());
            Map<String, String> avatarMap = new HashMap<>();
            for (String uname : uniqueUsernames) {
                avatarMap.put(uname, resolveAndCacheAvatar(uname));
            }
            livePosts.forEach(post -> post.put("avatarUrl", avatarMap.get((String) post.get("username"))));
            return ResponseEntity.ok(livePosts);
        } catch (Exception e) {
            log.error("Feed fetch failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/post/my-posts")
    public ResponseEntity<?> getMyPosts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestAttribute("userId") Long userId) {
        try {
            size = Math.min(Math.max(size, 1), 50); page = Math.max(page, 0);
            String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", user_id AS \"userId\", username FROM posts WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
            List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, userId, size, page * size);
            if (!livePosts.isEmpty()) {
                String liveAvatar = resolveAndCacheAvatar((String) livePosts.get(0).get("username"));
                livePosts.forEach(post -> post.put("avatarUrl", liveAvatar));
            }
            return ResponseEntity.ok(livePosts);
        } catch (Exception e) {
            log.error("My posts fetch failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
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
            if (file == null || file.isEmpty() || file.getContentType() == null || !file.getContentType().startsWith("image/") || file.getSize() > 8L * 1024 * 1024) {
                return ResponseEntity.badRequest().body(Map.of("error", "Profile picture must be an image under 8 MB."));
            }
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
            String denied = validateNewPost(userId, file, title, content, cityName, country);
            if (denied != null) return ResponseEntity.badRequest().body(Map.of("error", denied));
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
            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(cleanName);
                Map<String, Object> exactUser = SafeUsers.exact(remoteUser, cleanName);
                if (exactUser != null) {
                    Map<String, Object> safeProfile = new HashMap<>(exactUser);
                    if (safeProfile.get("profilePictureUrl") != null) {
                        redisTemplate.opsForValue().set("user:avatar:" + cleanName, safeProfile.get("profilePictureUrl"), java.time.Duration.ofHours(24));
                    }
                    safeProfile.put("avatarUrl", safeProfile.get("profilePictureUrl"));
                    return ResponseEntity.ok(safeProfile);
                }
            } catch (Exception ignored) {} // Gracefully degrade
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/moderation/report")
    public ResponseEntity<?> handleModerationReport(
            @RequestBody Map<String, Object> payload,
            @RequestAttribute(value = "userId", required = false) Long reporterId,
            @RequestAttribute(value = "username", required = false) String reporterUsername,
            @RequestHeader(value = "X-User-City", required = false) String reporterCity) {
        try {
            Long targetId = null;
            if (payload.containsKey("targetId") && payload.get("targetId") != null) {
                targetId = Long.valueOf(payload.get("targetId").toString());
            } else if (payload.containsKey("targetPostId") && payload.get("targetPostId") != null) {
                targetId = Long.valueOf(payload.get("targetPostId").toString());
            }

            if (targetId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Target ID missing."));
            }

            String reason = (String) payload.getOrDefault("reason", "Inappropriate Content");
            String type = (String) payload.get("type");

            String finalUsername = (reporterUsername != null) ? reporterUsername : "SystemUser";
            Long finalReporterId = (reporterId != null) ? reporterId : 1L;

            if ("COMMENT_REPORT".equals(type)) {
                socialService.submitCommentReport(finalReporterId, finalUsername, targetId, reason);
            } else {
                socialService.submitReport(finalReporterId, finalUsername, targetId, reason, reporterCity);
            }

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Report logged for review."));
        } catch (Exception e) {
            log.error("Report processing error", e);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Report logged for review."));
        }
    }

    // ---- moderation helpers -------------------------------------------------------------------------------------
    /** Posts reported by 3 or more different users are hidden from every feed until a human reviews them. */
    private static final String NOT_FLAGGED = " AND (SELECT COUNT(DISTINCT sa.reporter_id) FROM safety_alerts sa WHERE sa.target_post_id = posts.id) < 3 ";

    /** Simple per-user counter limit. Returns false when the limit is exceeded. */
    private boolean allow(String key, int max, java.time.Duration window) {
        try {
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1L) redisTemplate.expire(key, window);
            return n == null || n <= max;
        } catch (Exception e) {
            return true;   // never block users because Redis hiccuped
        }
    }

    private String validateNewPost(Long userId, MultipartFile file, String title, String content, String cityName, String country) {
        if (file == null || file.isEmpty()) return "A photo or video is required.";
        if (file.getSize() > 50L * 1024 * 1024) return "Media file is too large (max 50 MB).";
        String ct = file.getContentType();
        if (ct == null || !(ct.startsWith("image/") || ct.startsWith("video/"))) return "Only photos and videos can be uploaded.";
        if (title == null || title.isBlank() || title.length() > 20) return "Title must be 1-20 characters.";
        if (content == null || content.isBlank() || content.length() > 100) return "Description must be 1-100 characters.";
        if (cityName == null || cityName.isBlank() || cityName.length() > 50 || country == null || country.length() > 50) return "Please choose a valid city.";
        Integer known = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM radar_cities WHERE LOWER(city_name) = LOWER(?)", Integer.class, cityName.trim());
        if (known == null || known == 0) return "Unknown city.";
        String promo = PromoFilter.check(title);
        if (promo == null) promo = PromoFilter.check(content);
        if (promo != null) return promo;
        if (!allow("rl:post:" + userId, 20, java.time.Duration.ofHours(24))) return "Daily post limit reached (20). Try again tomorrow.";
        return null;
    }

    /**
     * Account deletion (Google Play requirement). Removes everything the user created, then the account itself.
     * Runs in one transaction: if the final account-deletion call fails, everything rolls back and the user can simply retry.
     */
    @DeleteMapping("/user/me")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> deleteMyAccount(@RequestAttribute("userId") Long userId, @RequestAttribute("username") String username) {
        final String name = username.trim().toLowerCase();
        try {
            // 1. media files of own posts + avatar
            List<Map<String, Object>> own = jdbcTemplate.queryForList("SELECT id, city_name, media_url FROM posts WHERE user_id = ?", userId);
            for (Map<String, Object> p : own) {
                Object url = p.get("media_url");
                if (url != null && url.toString().contains("/stream/")) {
                    try { blobClient.deleteMedia(url.toString().substring(url.toString().lastIndexOf('/') + 1)); } catch (Exception ignored) { }
                }
            }
            try {
                Map<String, Object> me = SafeUsers.exact(userCatalogClient.searchUsersByHandle(name), name);
                Object av = me == null ? null : me.get("profilePictureUrl");
                if (av != null && av.toString().contains("/stream/")) {
                    blobClient.deleteMedia(av.toString().substring(av.toString().lastIndexOf('/') + 1));
                }
            } catch (Exception ignored) { }

            // 2. posts this user touched on OTHER people's posts (to recompute counters afterwards)
            List<Long> touched = new ArrayList<>(jdbcTemplate.queryForList(
                    "SELECT post_id FROM post_upvotes WHERE user_id = ? UNION SELECT post_id FROM post_downvotes WHERE user_id = ? UNION SELECT post_id FROM comments WHERE user_id = ?",
                    Long.class, userId, userId, userId));

            // 3. own posts and everything attached to them
            String ownIds = "(SELECT id FROM posts WHERE user_id = ?)";
            jdbcTemplate.update("DELETE FROM comment_reports WHERE target_comment_id IN (SELECT id FROM comments WHERE post_id IN " + ownIds + ")", userId);
            jdbcTemplate.update("DELETE FROM comments WHERE post_id IN " + ownIds, userId);
            jdbcTemplate.update("DELETE FROM post_upvotes WHERE post_id IN " + ownIds, userId);
            jdbcTemplate.update("DELETE FROM post_downvotes WHERE post_id IN " + ownIds, userId);
            jdbcTemplate.update("DELETE FROM safety_alerts WHERE target_post_id IN " + ownIds, userId);
            jdbcTemplate.update("DELETE FROM posts WHERE user_id = ?", userId);

            // 4. own comments / votes / reports on other people's content
            jdbcTemplate.update("UPDATE comments SET parent_id = NULL WHERE parent_id IN (SELECT id FROM comments WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM comment_reports WHERE target_comment_id IN (SELECT id FROM comments WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM comments WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM post_upvotes WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM post_downvotes WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM safety_alerts WHERE reporter_id = ?", userId);
            jdbcTemplate.update("DELETE FROM comment_reports WHERE reporter_id = ?", userId);
            jdbcTemplate.update("DELETE FROM peer_vouches WHERE voucher_id = ? OR target_user_id = ?", userId, userId);

            // 5. recompute counters on the posts that remain
            for (Long postId : touched) {
                jdbcTemplate.update("UPDATE posts SET score = (SELECT COUNT(*) FROM post_upvotes WHERE post_id = ?) - (SELECT COUNT(*) FROM post_downvotes WHERE post_id = ?), comment_count = (SELECT COUNT(*) FROM comments WHERE post_id = ?) WHERE id = ?", postId, postId, postId, postId);
            }

            // 6. caches
            try {
                for (Map<String, Object> p : own) {
                    redisTemplate.delete("post:object:" + p.get("id"));
                    redisTemplate.opsForZSet().remove("feed:city:" + String.valueOf(p.get("city_name")).toLowerCase().replace(" ", "_"), p.get("id").toString());
                }
                redisTemplate.delete(java.util.List.of("user:id_to_name:" + userId, "user:avatar:" + name, "user:strikers:" + name, "user:profile:" + userId, "dp:v1:" + name));
            } catch (Exception ignored) { }

            // 7. the account itself (user-catalog service). Throws on failure -> transaction rolls back -> user retries.
            userCatalogClient.deleteInternalUser(name);
            log.warn("Account deleted: @{}", name);
            return ResponseEntity.ok(Map.of("status", "SUCCESS"));
        } catch (Exception e) {
            log.error("Account deletion failed for @{}", name, e);
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Could not delete the account right now. Please try again."));
        }
    }
}
