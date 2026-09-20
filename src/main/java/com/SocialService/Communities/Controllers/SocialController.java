package com.SocialService.Communities.Controllers;

import com.SocialService.Communities.Clients.UserCatalogClient;
import com.SocialService.Communities.Models.Post;
import com.SocialService.Communities.Models.RadarContinent;
import com.SocialService.Communities.Repositories.PostRepository;
import com.SocialService.Communities.Repositories.RadarContinentRepository;
import com.SocialService.Communities.Repositories.SocialService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @GetMapping(value = "/notifications/subscribe", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToNotificationStream(@RequestAttribute("userId") Long userId) {
        return notificationRegistryService.registerClient(userId);
    }

    @PostMapping("/post/create")
    public ResponseEntity<?> createPost(@RequestBody Post post, @RequestAttribute("userId") Long userId) {
        try {
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
            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(uname.trim().toLowerCase());
                if (remoteUser != null && !remoteUser.isEmpty()) avatarMap.put(uname, (String) remoteUser.get(0).get("profilePictureUrl"));
            } catch (Exception e) {}
        }
        livePosts.forEach(post -> post.put("avatarUrl", avatarMap.get((String) post.get("username"))));

        targetPayload.put("type", "POSTS");
        targetPayload.put("results", livePosts);
        return ResponseEntity.ok(targetPayload);
    }

    @PutMapping("/user/profile/update-direct")
    public ResponseEntity<?> updateProfileDataDirectly(@RequestAttribute("userId") Long userId, @RequestBody Map<String, String> body) {
        try {
            String newPic = body.get("profilePictureUrl");
            if (newPic != null) redisTemplate.opsForHash().put("user:profile:" + userId, "avatarUrl", newPic);
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
    public ResponseEntity<?> addSecureComment(@PathVariable Long postId, @Valid @RequestBody com.SocialService.Communities.DTOs.CommentRequestDTO request, @RequestAttribute("userId") Long userId) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(socialService.addSecureComment(postId, userId, request.getContent(), request.getParentId()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/feed")
    public ResponseEntity<List<Map<String, Object>>> getCityFeed(@RequestParam String city, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", user_id AS \"userId\", username FROM posts WHERE LOWER(city_name) = LOWER(?) ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, city.trim(), size, page * size);

        Set<String> uniqueUsernames = livePosts.stream().map(p -> (String) p.get("username")).collect(Collectors.toSet());
        Map<String, String> avatarMap = new HashMap<>();
        for (String uname : uniqueUsernames) {
            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(uname.trim().toLowerCase());
                if (remoteUser != null && !remoteUser.isEmpty()) avatarMap.put(uname, (String) remoteUser.get(0).get("profilePictureUrl"));
            } catch (Exception e) {}
        }
        livePosts.forEach(post -> post.put("avatarUrl", avatarMap.get((String) post.get("username"))));
        return ResponseEntity.ok(livePosts);
    }

    @GetMapping("/post/my-posts")
    public ResponseEntity<List<Map<String, Object>>> getMyPosts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestAttribute("userId") Long userId) {
        String sql = "SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", user_id AS \"userId\", username FROM posts WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> livePosts = jdbcTemplate.queryForList(sql, userId, size, page * size);

        if (!livePosts.isEmpty()) {
            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(((String) livePosts.get(0).get("username")).trim().toLowerCase());
                if (remoteUser != null && !remoteUser.isEmpty()) {
                    String liveAvatar = (String) remoteUser.get(0).get("profilePictureUrl");
                    livePosts.forEach(post -> post.put("avatarUrl", liveAvatar));
                }
            } catch (Exception ignored) {}
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
            Map<String, Object> blobResponse = blobClient.uploadMedia(file, String.valueOf(userId));
            String newPicUrl = (String) blobResponse.get("mediaUrl");
            if (newPicUrl == null || newPicUrl.isEmpty()) throw new IllegalStateException("Empty URL");

            userCatalogClient.updateInternalAvatar(username.trim().toLowerCase(), Map.of("profilePictureUrl", newPicUrl));
            redisTemplate.opsForHash().put("user:profile:" + userId, "avatarUrl", newPicUrl);

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "avatarUrl", newPicUrl));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/post/upload-and-create", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndCreatePost(@RequestAttribute("userId") Long userId, @RequestAttribute("username") String username, @RequestParam("file") MultipartFile file, @RequestParam("title") String title, @RequestParam("content") String content, @RequestParam("cityName") String cityName, @RequestParam("country") String country) {
        try {
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

    @GetMapping("/post/{postId}/comments")
    public ResponseEntity<List<com.SocialService.Communities.DTOs.CommentResponseDTO>> getComments(@PathVariable Long postId) {
        // 🟢 FIX: PostgreSQL aliases are strictly avoided here. Retrieving exact raw database column names prevents the 500 NullPointerException.
        String sql = "SELECT id, content, parent_id, created_at, user_id, username FROM comments WHERE post_id = ? ORDER BY created_at ASC";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, postId);

            Set<String> uniqueUsernames = rows.stream().map(r -> (String) r.get("username")).filter(u -> u != null).collect(Collectors.toSet());
            Map<String, String> avatarMap = new HashMap<>();
            for (String uname : uniqueUsernames) {
                try {
                    List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(uname.trim().toLowerCase());
                    if (remoteUser != null && !remoteUser.isEmpty()) avatarMap.put(uname, (String) remoteUser.get(0).get("profilePictureUrl"));
                } catch (Exception ignored) {}
            }

            List<com.SocialService.Communities.DTOs.CommentResponseDTO> allComments = new java.util.ArrayList<>();
            Map<Long, List<com.SocialService.Communities.DTOs.CommentResponseDTO>> childrenMap = new java.util.HashMap<>();
            List<com.SocialService.Communities.DTOs.CommentResponseDTO> rootComments = new java.util.ArrayList<>();

            for (Map<String, Object> row : rows) {
                String rowUser = (String) row.get("username");
                Object parentObj = row.get("parent_id");
                Object createdObj = row.get("created_at");

                com.SocialService.Communities.DTOs.CommentResponseDTO dto = com.SocialService.Communities.DTOs.CommentResponseDTO.builder()
                        .id(((Number) row.get("id")).longValue())
                        .parentId(parentObj != null ? ((Number) parentObj).longValue() : null)
                        .content((String) row.get("content"))
                        .username(rowUser)
                        .avatarUrl(avatarMap.get(rowUser))
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

    @DeleteMapping("/post/{postId}/delete")
    public ResponseEntity<?> purgePostRecord(@PathVariable Long postId, @RequestAttribute("userId") Long userId) {
        try {
            Map<String, Object> postData = jdbcTemplate.queryForMap("SELECT user_id, media_url FROM posts WHERE id = ?", postId);
            if (((Number) postData.get("user_id")).longValue() != userId) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Unauthorized"));

            if (postData.get("media_url") != null && postData.get("media_url").toString().contains("/stream/")) {
                String mediaUrl = postData.get("media_url").toString();
                try { blobClient.deleteMedia(mediaUrl.substring(mediaUrl.lastIndexOf("/") + 1)); } catch (Exception e) {}
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
            try {
                List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(((String) post.get("username")).trim().toLowerCase());
                if (remoteUser != null && !remoteUser.isEmpty()) post.put("avatarUrl", remoteUser.get(0).get("profilePictureUrl"));
            } catch (Exception ignored) {}
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Post not found."));
        }
    }

    @GetMapping("/user/{username}/profile")
    public ResponseEntity<?> getUserProfileData(@PathVariable String username) {
        try {
            List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(username.trim().toLowerCase());
            if (remoteUser != null && !remoteUser.isEmpty()) {
                Map<String, Object> safeProfile = new HashMap<>(remoteUser.get(0));
                safeProfile.put("avatarUrl", safeProfile.get("profilePictureUrl"));
                return ResponseEntity.ok(safeProfile);
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/user/{username}/full-profile")
    public ResponseEntity<?> getFullProfile(@PathVariable String username) {
        Map<String, Object> response = new HashMap<>();
        String cleanUsername = username.trim().toLowerCase();

        try {
            List<Map<String, Object>> remoteUser = userCatalogClient.searchUsersByHandle(cleanUsername);
            if (remoteUser != null && !remoteUser.isEmpty()) {
                Map<String, Object> safeProfile = new HashMap<>(remoteUser.get(0));
                safeProfile.put("avatarUrl", safeProfile.get("profilePictureUrl"));
                response.put("profile", safeProfile);
            } else return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User untraceable."));

            List<Map<String, Object>> userPosts = jdbcTemplate.queryForList("SELECT id, title, content, media_url AS \"mediaUrl\", media_type AS \"mediaType\", score, comment_count AS \"commentCount\", created_at AS \"createdAt\", city_name AS \"cityName\" FROM posts WHERE username = ? ORDER BY created_at DESC", cleanUsername);
            String liveAvatarUrl = (String) remoteUser.get(0).get("profilePictureUrl");
            userPosts.forEach(post -> { post.put("avatarUrl", liveAvatarUrl); post.put("username", cleanUsername); });

            response.put("posts", userPosts);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}