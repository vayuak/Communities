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

        String sql = "SELECT p.id, p.title, p.content, p.media_url AS \"mediaUrl\", p.media_type AS \"mediaType\", " +
                "p.score, p.comment_count AS \"commentCount\", p.created_at AS \"createdAt\", " +
                "p.user_id AS \"userId\", u.username, u.profile_picture_url AS \"avatarUrl\" " +
                "FROM posts p " +
                "LEFT JOIN users u ON p.user_id = u.id " +
                "WHERE LOWER(p.content) LIKE LOWER(?) OR LOWER(p.title) LIKE LOWER(?) " +
                "ORDER BY p.created_at DESC LIMIT ? OFFSET ?";

        String searchParam = "%" + input + "%";
        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, searchParam, searchParam, size, page * size);

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
            jdbcTemplate.update("UPDATE users SET profile_picture_url = ? WHERE id = ?", newPic, userId);
            String profileKey = "user:profile:" + userId;
            if (newPic != null) {
                redisTemplate.opsForHash().put(profileKey, "avatarUrl", newPic);
            }
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

    @PostMapping("/feed")
    public ResponseEntity<List<Map<String, Object>>> getCityFeed(
            @RequestParam String city,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String sql = "SELECT p.id, p.title, p.content, p.media_url AS \"mediaUrl\", p.media_type AS \"mediaType\", " +
                "p.score, p.comment_count AS \"commentCount\", p.created_at AS \"createdAt\", " +
                "p.user_id AS \"userId\", u.username, u.profile_picture_url AS \"avatarUrl\" " +
                "FROM posts p " +
                "LEFT JOIN users u ON p.user_id = u.id " +
                "WHERE p.city_name = ? " +
                "ORDER BY p.created_at DESC LIMIT ? OFFSET ?";

        List<Map<String, Object>> liveFeed = jdbcTemplate.queryForList(sql, city, size, page * size);
        return ResponseEntity.ok(liveFeed);
    }

    @GetMapping("/post/my-posts")
    public ResponseEntity<List<Map<String, Object>>> getMyPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestAttribute("userId") Long userId) {

        String sql = "SELECT p.id, p.title, p.content, p.media_url AS \"mediaUrl\", p.media_type AS \"mediaType\", " +
                "p.score, p.comment_count AS \"commentCount\", p.created_at AS \"createdAt\", " +
                "p.user_id AS \"userId\", u.username, u.profile_picture_url AS \"avatarUrl\" " +
                "FROM posts p " +
                "LEFT JOIN users u ON p.user_id = u.id " +
                "WHERE p.user_id = ? " +
                "ORDER BY p.created_at DESC LIMIT ? OFFSET ?";

        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, userId, size, page * size);
        return ResponseEntity.ok(livePosts);
    }

    // 🟢 FIX: Unified and Bulletproof Comment Deletion (targets comments table and flattens hierarchy)
    @DeleteMapping("/post/comment/{commentId}")
    public ResponseEntity<?> deleteComment(
            @PathVariable Long commentId,
            @RequestAttribute("userId") Long userId) {
        try {
            // 1. Verify Ownership & Retrieve the associated Post ID
            String checkSql = "SELECT user_id, post_id FROM comments WHERE id = ?";
            Map<String, Object> commentData = jdbcTemplate.queryForMap(checkSql, commentId);

            if (((Number) commentData.get("user_id")).longValue() != userId) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Unauthorized to delete this comment."));
            }

            Long postId = ((Number) commentData.get("post_id")).longValue();

            // 2. Flatten Hierarchy to prevent foreign key constraint crashes
            // If anyone replied to this comment, detach them before deleting it.
            jdbcTemplate.update("UPDATE comments SET parent_id = NULL WHERE parent_id = ?", commentId);

            // 3. Final Deletion
            jdbcTemplate.update("DELETE FROM comments WHERE id = ?", commentId);

            // 4. Keep Post stats accurate by decrementing the comment count safely
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

            // 1. Update remote User Catalog (Auth Service)
            userCatalogClient.updateInternalAvatar(username, Map.of("profilePictureUrl", newPicUrl));

            // 2. Update Redis Cache
            String profileKey = "user:profile:" + userId;
            redisTemplate.opsForHash().put(profileKey, "avatarUrl", newPicUrl);

            // 3. Update Local Social DB so the Feed's SQL JOIN sees the new avatar!
            jdbcTemplate.update("UPDATE users SET profile_picture_url = ? WHERE id = ?", newPicUrl, userId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Profile picture synced globally.",
                    "avatarUrl", newPicUrl
            ));
        } catch (Exception e) {
            log.error("Global Profile Sync Failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Profile synchronization failed across nodes."));
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
        String sql = "SELECT c.id, c.content, c.parent_id AS parentId, c.created_at AS createdAt, c.user_id AS userId, u.username, u.profile_picture_url AS avatarUrl FROM comments c JOIN users u ON c.user_id = u.id WHERE c.post_id = ? ORDER BY c.created_at ASC";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, postId);
            List<com.SocialService.Communities.DTOs.CommentResponseDTO> allComments = new java.util.ArrayList<>();
            Map<Long, List<com.SocialService.Communities.DTOs.CommentResponseDTO>> childrenMap = new java.util.HashMap<>();
            List<com.SocialService.Communities.DTOs.CommentResponseDTO> rootComments = new java.util.ArrayList<>();

            for (Map<String, Object> row : rows) {
                com.SocialService.Communities.DTOs.CommentResponseDTO dto = com.SocialService.Communities.DTOs.CommentResponseDTO.builder()
                        .id(((Number) row.get("id")).longValue())
                        .parentId(row.get("parentId") != null ? ((Number) row.get("parentId")).longValue() : null)
                        .content((String) row.get("content"))
                        .username((String) row.get("username"))
                        .avatarUrl((String) row.get("avatarUrl"))
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
            String sql = "SELECT username, profile_picture_url AS avatarUrl, is_premium AS isPremium FROM users WHERE username = ?";
            Map<String, Object> userProfile = jdbcTemplate.queryForMap(sql, username.toLowerCase().trim());
            return ResponseEntity.ok(userProfile);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/debug/flush-redis")
    public ResponseEntity<String> flushEmbeddedRedis() {
        if (redisTemplate.getConnectionFactory() != null) {
            redisTemplate.getConnectionFactory().getConnection().flushAll();
        }
        return ResponseEntity.ok("In-Memory Redis cache cleared!");
    }

    // 🟢 Bulletproof Deletion Waterfall - Uses Feign Client to bypass ShieldHandshakeFilter
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

            // 1. Wipe Media Vault Remotely using authorized Feign Client
            if (postData.get("media_url") != null && postData.get("media_url").toString().contains("/stream/")) {
                String mediaUrl = postData.get("media_url").toString();
                String mediaId = mediaUrl.substring(mediaUrl.lastIndexOf("/") + 1);
                try {
                    // No more RestTemplate. We use the internal blobClient to pass the security handshake!
                    blobClient.deleteMedia(mediaId);
                    log.info("Media vault successfully purged for mediaId: {}", mediaId);
                } catch (Exception blobEx) {
                    log.error("Failed to clear media file from storage sector: {}", blobEx.getMessage());
                }
            }

            // 2. Clear Database Integrity Tree Safely (Wipe constraints first)
            jdbcTemplate.update("UPDATE comments SET parent_id = NULL WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM comments WHERE post_id = ?", postId);

            jdbcTemplate.update("DELETE FROM post_upvotes WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM post_downvotes WHERE post_id = ?", postId);

            // 3. Clear Post
            jdbcTemplate.update("DELETE FROM posts WHERE id = ?", postId);

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Post completely purged."));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Post not found."));
        } catch (Exception e) {
            log.error("Post deletion cascade failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Database blocked deletion: " + e.getMessage()));
        }
    }
}