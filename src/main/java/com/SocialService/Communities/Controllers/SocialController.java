package com.SocialService.Communities.Controllers;

import com.SocialService.Communities.Clients.UserCatalogClient;
import com.SocialService.Communities.Models.Post;
import com.SocialService.Communities.Models.RadarContinent;
import com.SocialService.Communities.Repositories.PostRepository;
import com.SocialService.Communities.Repositories.RadarContinentRepository;
import com.SocialService.Communities.Repositories.SocialService;
import com.SocialService.Communities.Clients.BlobClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @GetMapping(value = "/notifications/subscribe", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToNotificationStream(@RequestAttribute("userId") Long userId) {
        return notificationRegistryService.registerClient(userId);
    }

    @PostMapping("/post/create")
    public ResponseEntity<?> createPost(
            @RequestBody Post post,
            @RequestAttribute("userId") Long userId) {
        try {
            Post savedPost = socialService.createPost(post, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(savedPost);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PostMapping("/post/comment")
    public ResponseEntity<?> addComment(
            @RequestBody com.SocialService.Communities.Models.Comment comment,
            @RequestAttribute("userId") Long userId) {
        try {
            com.SocialService.Communities.Models.Comment savedComment = socialService.addComment(comment, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(savedComment);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchGlobalScamDatabase(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestAttribute("userId") Long userId) {

        String input = keyword.trim();
        Map<String, Object> targetPayload = new HashMap<>();

        if (input.startsWith("@") && input.length() > 1) {
            String targetHandle = input.substring(1);
            List<Map<String, Object>> remoteUsers = userCatalogClient.searchUsersByHandle(targetHandle);
            targetPayload.put("type", "USERS");
            targetPayload.put("results", remoteUsers);
            return ResponseEntity.ok(targetPayload);
        }

        String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", " +
                "score, comment_count AS \"commentCount\", created_at AS \"createdAt\", " +
                "user_id AS \"userId\", username " +
                "FROM posts " +
                "WHERE LOWER(content) LIKE LOWER(?) OR LOWER(title) LIKE LOWER(?) " +
                "ORDER BY created_at DESC LIMIT ? OFFSET ?";

        String searchParam = "%" + input + "%";
        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, searchParam, searchParam, size, page * size);

        java.util.Set<String> uniqueUsernames = livePosts.stream()
                .map(p -> (String) p.get("username"))
                .collect(java.util.stream.Collectors.toSet());

        Map<String, String> avatarMap = new HashMap<>();
        for (String uname : uniqueUsernames) {
            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(uname);
                if (remoteUser != null && !remoteUser.isEmpty()) {
                    avatarMap.put(uname, (String) remoteUser.get(0).get("profilePictureUrl"));
                }
            } catch (Exception e) {
                log.warn("Could not fetch avatar for {}: {}", uname, e.getMessage());
            }
        }

        livePosts.forEach(post -> {
            String author = (String) post.get("username");
            post.put("avatarUrl", avatarMap.get(author));
        });

        targetPayload.put("type", "POSTS");
        targetPayload.put("results", livePosts);
        return ResponseEntity.ok(targetPayload);
    }

    @PutMapping("/user/profile/update-direct")
    public ResponseEntity<?> updateProfileDataDirectly(
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, String> body) {
        try {
            String newPic = body.get("profilePictureUrl");
            String profileKey = "user:profile:" + userId;
            if (newPic != null) {
                redisTemplate.opsForHash().put(profileKey, "avatarUrl", newPic);
            }
            // 🟢 CRASH FIX: Removed jdbcTemplate.update("UPDATE users...") here
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Profile picture synchronized successfully."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/meta/tier-one-cities")
    public ResponseEntity<?> getTierOneGlobalMatrix() {
        List<RadarContinent> rawData = radarContinentRepository.findAll();
        List<Map<String, Object>> responseMatrix = rawData.stream().map(record -> {
            Map<String, Object> map = new HashMap<>();
            map.put("continent", record.getName());
            map.put("cities", record.getCities().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
            return map;
        }).toList();
        return ResponseEntity.ok(responseMatrix);
    }

    @PostMapping("/post/{postId}/vote")
    public ResponseEntity<?> voteOnPost(
            @PathVariable Long postId,
            @RequestParam String direction,
            @RequestAttribute("userId") Long userId) {
        try {
            String result = socialService.votePost(postId, userId, direction);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", result));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/post/{postId}/share")
    public ResponseEntity<?> sharePost(@PathVariable Long postId) {
        try {
            socialService.incrementShareCount(postId);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Post shared successfully."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/post/{postId}/comment")
    public ResponseEntity<?> addSecureComment(
            @PathVariable Long postId,
            @Valid @RequestBody com.SocialService.Communities.DTOs.CommentRequestDTO request,
            @RequestAttribute("userId") Long userId) {
        try {
            com.SocialService.Communities.Models.Comment savedComment =
                    socialService.addSecureComment(postId, userId, request.getContent(), request.getParentId());

            return ResponseEntity.status(HttpStatus.CREATED).body(savedComment);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/feed")
    public ResponseEntity<List<Map<String, Object>>> getCityFeed(
            @RequestParam String city,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", " +
                "score, comment_count AS \"commentCount\", created_at AS \"createdAt\", " +
                "user_id AS \"userId\", username " +
                "FROM posts " +
                "WHERE LOWER(city_name) = LOWER(?) " +
                "ORDER BY created_at DESC LIMIT ? OFFSET ?";

        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, city.trim(), size, page * size);

        java.util.Set<String> uniqueUsernames = livePosts.stream()
                .map(p -> (String) p.get("username"))
                .collect(java.util.stream.Collectors.toSet());

        Map<String, String> avatarMap = new HashMap<>();
        for (String uname : uniqueUsernames) {
            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(uname);
                if (remoteUser != null && !remoteUser.isEmpty()) {
                    avatarMap.put(uname, (String) remoteUser.get(0).get("profilePictureUrl"));
                }
            } catch (Exception e) {
                log.warn("Could not fetch avatar for {}: {}", uname, e.getMessage());
            }
        }

        livePosts.forEach(post -> {
            String author = (String) post.get("username");
            post.put("avatarUrl", avatarMap.get(author));
        });

        return ResponseEntity.ok(livePosts);
    }

    @GetMapping("/post/my-posts")
    public ResponseEntity<List<Map<String, Object>>> getMyPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestAttribute("userId") Long userId) {

        // 🟢 CRASH FIX: Dropped LEFT JOIN users table, fetching avatars dynamically
        String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", " +
                "score, comment_count AS \"commentCount\", created_at AS \"createdAt\", " +
                "user_id AS \"userId\", username " +
                "FROM posts " +
                "WHERE user_id = ? " +
                "ORDER BY created_at DESC LIMIT ? OFFSET ?";

        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, userId, size, page * size);

        if (!livePosts.isEmpty()) {
            String author = (String) livePosts.get(0).get("username");
            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(author);
                if (remoteUser != null && !remoteUser.isEmpty()) {
                    String liveAvatar = (String) remoteUser.get(0).get("profilePictureUrl");
                    livePosts.forEach(post -> post.put("avatarUrl", liveAvatar));
                }
            } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(livePosts);
    }

    @DeleteMapping("/post/comment/{commentId}")
    public ResponseEntity<?> deleteComment(
            @PathVariable Long commentId,
            @RequestAttribute("userId") Long userId) {
        try {
            String checkSql = "SELECT user_id, post_id FROM comments WHERE id = ?";
            Map<String, Object> commentData = jdbcTemplate.queryForMap(checkSql, commentId);

            if (((Number) commentData.get("user_id")).longValue() != userId) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Unauthorized to delete this comment."));
            }

            Long postId = ((Number) commentData.get("post_id")).longValue();

            jdbcTemplate.update("UPDATE comments SET parent_id = NULL WHERE parent_id = ?", commentId);
            jdbcTemplate.update("DELETE FROM comments WHERE id = ?", commentId);
            jdbcTemplate.update("UPDATE posts SET comment_count = GREATEST(COALESCE(comment_count, 0) - 1, 0) WHERE id = ?", postId);

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Comment deleted."));

        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Comment not found."));
        } catch (Exception e) {
            log.error("Comment deletion failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Database blocked deletion: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/user/profile/upload-and-update", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndUpdateProfile(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username,
            @RequestParam("file") MultipartFile file) {

        try {
            Map<String, Object> blobResponse = blobClient.uploadMedia(file, String.valueOf(userId));
            String newPicUrl = (String) blobResponse.get("mediaUrl");

            if (newPicUrl == null || newPicUrl.isEmpty()) {
                throw new IllegalStateException("Media Vault returned an empty media URL.");
            }

            userCatalogClient.updateInternalAvatar(username, Map.of("profilePictureUrl", newPicUrl));

            String profileKey = "user:profile:" + userId;
            redisTemplate.opsForHash().put(profileKey, "avatarUrl", newPicUrl);

            // 🟢 CRASH FIX: Removed jdbcTemplate.update("UPDATE users...") here

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Profile picture synced globally.",
                    "avatarUrl", newPicUrl
            ));
        } catch (feign.FeignException e) {
            log.error("🔴 FEIGN CROSS-NODE CALL FAILED! Status: {}, Body: {}", e.status(), e.contentUTF8());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Cross-node sync failed: " + e.contentUTF8()));
        } catch (Exception e) {
            log.error("🔴 GLOBAL PROFILE SYNC FAILED AT NODE:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Profile synchronization failed: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/post/upload-and-create", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndCreatePost(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username,
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam("content") String content,
            @RequestParam("cityName") String cityName,
            @RequestParam("country") String country) {
        try {
            Map<String, Object> blobResponse = blobClient.uploadMedia(file, String.valueOf(userId));
            String mediaUrl = (String) blobResponse.get("mediaUrl");
            String mediaType = (String) blobResponse.get("mediaType");

            Post post = new Post();
            post.setTitle(title);
            post.setContent(content);
            post.setCityName(cityName);
            post.setCity(cityName);
            post.setCountry(country);
            post.setMediaUrl(mediaUrl);
            post.setMediaType(mediaType);
            post.setUsername(username);

            Post savedPost = socialService.createPost(post, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(savedPost);
        } catch (Exception e) {
            log.error("Global Post Creation Failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Post synchronization failed across nodes."));
        }
    }

    @PostMapping(value = "/chat/media/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadSecureChatMedia(
            @RequestAttribute("userId") Long userId,
            @RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> blobResponse = blobClient.uploadMedia(file, String.valueOf(userId));
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "mediaUrl", blobResponse.get("mediaUrl"),
                    "mediaType", blobResponse.get("mediaType")
            ));
        } catch (Exception e) {
            log.error("Secure Media Upload Failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to vault secure media."));
        }
    }

    @GetMapping("/post/{postId}/comments")
    public ResponseEntity<List<com.SocialService.Communities.DTOs.CommentResponseDTO>> getComments(@PathVariable Long postId) {
        // 🟢 CRASH FIX: Dropped JOIN users table, dynamically fetching via User Catalog
        String sql = "SELECT id, content, parent_id AS parentId, created_at AS createdAt, user_id AS userId, username FROM comments WHERE post_id = ? ORDER BY created_at ASC";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, postId);

            java.util.Set<String> uniqueUsernames = rows.stream()
                    .map(r -> (String) r.get("username"))
                    .filter(u -> u != null)
                    .collect(java.util.stream.Collectors.toSet());

            Map<String, String> avatarMap = new HashMap<>();
            for (String uname : uniqueUsernames) {
                try {
                    List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(uname);
                    if (remoteUser != null && !remoteUser.isEmpty()) {
                        avatarMap.put(uname, (String) remoteUser.get(0).get("profilePictureUrl"));
                    }
                } catch (Exception ignored) {}
            }

            List<com.SocialService.Communities.DTOs.CommentResponseDTO> allComments = new java.util.ArrayList<>();
            Map<Long, List<com.SocialService.Communities.DTOs.CommentResponseDTO>> childrenMap = new java.util.HashMap<>();
            List<com.SocialService.Communities.DTOs.CommentResponseDTO> rootComments = new java.util.ArrayList<>();

            for (Map<String, Object> row : rows) {
                String rowUser = (String) row.get("username");
                com.SocialService.Communities.DTOs.CommentResponseDTO dto = com.SocialService.Communities.DTOs.CommentResponseDTO.builder()
                        .id(((Number) row.get("id")).longValue())
                        .parentId(row.get("parentId") != null ? ((Number) row.get("parentId")).longValue() : null)
                        .content((String) row.get("content"))
                        .username(rowUser)
                        .avatarUrl(avatarMap.get(rowUser)) // Injected Dynamically
                        .createdAt(row.get("createdAt") != null ? ((java.sql.Timestamp) row.get("createdAt")).toLocalDateTime() : null)
                        .build();
                allComments.add(dto);

                if (dto.getParentId() == null) {
                    rootComments.add(dto);
                } else {
                    childrenMap.computeIfAbsent(dto.getParentId(), k -> new java.util.ArrayList<>()).add(dto);
                }
            }

            List<com.SocialService.Communities.DTOs.CommentResponseDTO> sortedNestingList = new java.util.ArrayList<>();
            java.util.Stack<com.SocialService.Communities.DTOs.CommentResponseDTO> stack = new java.util.Stack<>();

            for (int i = rootComments.size() - 1; i >= 0; i--) {
                stack.push(rootComments.get(i));
            }

            while (!stack.isEmpty()) {
                com.SocialService.Communities.DTOs.CommentResponseDTO current = stack.pop();
                sortedNestingList.add(current);
                List<com.SocialService.Communities.DTOs.CommentResponseDTO> children = childrenMap.get(current.getId());
                if (children != null) {
                    for (int i = children.size() - 1; i >= 0; i--) {
                        stack.push(children.get(i));
                    }
                }
            }
            return ResponseEntity.ok(sortedNestingList);
        } catch (Exception e) {
            log.error("Failed to fetch comments: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("/user/{username}/profile")
    public ResponseEntity<?> getUserProfileData(@PathVariable String username) {
        try {
            // Fetch directly from User Catalog instead of local missing DB
            List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(username.trim().toLowerCase());
            if (remoteUser != null && !remoteUser.isEmpty()) {
                return ResponseEntity.ok(remoteUser.get(0));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/user/{username}/full-profile")
    public ResponseEntity<?> getFullProfile(@PathVariable String username) {
        Map<String, Object> response = new HashMap<>();

        try {
            List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(username);
            if (remoteUser != null && !remoteUser.isEmpty()) {
                response.put("profile", remoteUser.get(0));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "User identity untraceable."));
            }
            String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", " +
                    "score, comment_count AS \"commentCount\", created_at AS \"createdAt\", city_name AS \"cityName\" " +
                    "FROM posts WHERE username = ? ORDER BY created_at DESC";

            List<Map<String, Object>> userPosts = jdbcTemplate.queryForList(sql, username);

            String liveAvatarUrl = (String) remoteUser.get(0).get("profilePictureUrl");
            userPosts.forEach(post -> {
                post.put("avatarUrl", liveAvatarUrl);
                post.put("username", username);
            });

            response.put("posts", userPosts);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Profile aggregation failed for {}: {}", username, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to aggregate profile vectors."));
        }
    }

    @DeleteMapping("/post/{postId}/delete")
    public ResponseEntity<?> purgePostRecord(
            @PathVariable Long postId,
            @RequestAttribute("userId") Long userId) {
        try {
            String checkSql = "SELECT user_id, media_url FROM posts WHERE id = ?";
            Map<String, Object> postData = jdbcTemplate.queryForMap(checkSql, postId);

            if (((Number) postData.get("user_id")).longValue() != userId) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized to delete this post."));
            }

            if (postData.get("media_url") != null && postData.get("media_url").toString().contains("/stream/")) {
                String mediaUrl = postData.get("media_url").toString();
                String mediaId = mediaUrl.substring(mediaUrl.lastIndexOf("/") + 1);
                try {
                    blobClient.deleteMedia(mediaId);
                    log.info("Media vault successfully purged for mediaId: {}", mediaId);
                } catch (Exception blobEx) {
                    log.error("Failed to clear media file from storage sector: {}", blobEx.getMessage());
                }
            }

            jdbcTemplate.update("UPDATE comments SET parent_id = NULL WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM comments WHERE post_id = ?", postId);

            jdbcTemplate.update("DELETE FROM post_upvotes WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM post_downvotes WHERE post_id = ?", postId);

            jdbcTemplate.update("DELETE FROM posts WHERE id = ?", postId);

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Post completely purged."));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Post not found."));
        } catch (Exception e) {
            log.error("Post deletion cascade failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Database blocked deletion: " + e.getMessage()));
        }
    }

    @GetMapping("/post/{postId}")
    public ResponseEntity<?> getSinglePost(@PathVariable Long postId) {
        try {
            // 🟢 CRASH FIX: Dropped LEFT JOIN users table here as well
            String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", " +
                    "score, comment_count AS \"commentCount\", created_at AS \"createdAt\", " +
                    "user_id AS \"userId\", username " +
                    "FROM posts " +
                    "WHERE id = ?";

            Map<String, Object> post = jdbcTemplate.queryForMap(sql, postId);

            try {
                String author = (String) post.get("username");
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(author);
                if (remoteUser != null && !remoteUser.isEmpty()) {
                    post.put("avatarUrl", remoteUser.get(0).get("profilePictureUrl"));
                }
            } catch (Exception ignored) {}

            return ResponseEntity.ok(post);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Post not found."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to load post."));
        }
    }
}