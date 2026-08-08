package com.SocialService.Communities.Services;

import com.SocialService.Communities.Clients.UserClient;
import com.SocialService.Communities.DTOs.CommentResponseDTO;
import com.SocialService.Communities.Models.*;
import com.SocialService.Communities.Repositories.*;
import com.SocialService.Communities.DTOs.FeedPostDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SocialServiceImpl implements SocialService {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    private final PeerVouchRepository peerVouchRepository;

    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final ProfileCacheService profileCacheService;
    private final UserClient userClient;

    // ==========================================
    // 🌍 CORE SUBMISSION ENGINE & FEED MANAGEMENT
    // ==========================================
    @Override
    @Transactional
    public Post createPost(Post post, Long userId) {
        if (post.getMediaUrl() == null || post.getMediaUrl().trim().isEmpty()) {
            throw new RuntimeException("A media attachment is mandatory.");
        }
        if (post.getCityName() == null || post.getCityName().trim().isEmpty()) {
            throw new RuntimeException("Target city is mandatory.");
        }

        if (post.getTitle() != null && post.getTitle().length() > 20) {
            throw new RuntimeException("Title too long. Max 20 characters.");
        }
        if (post.getContent() != null && post.getContent().length() > 100) {
            throw new RuntimeException("Description too long. Max 100 characters.");
        }

        post.setUserId(userId);
        post.setScore(0);
        post.setCategory("GLOBAL_POST");

        Post savedPost = postRepository.save(post);

        try {
            String cityKey = "feed:city:" + savedPost.getCityName().toLowerCase().replace(" ", "_");
            long timestampScore = savedPost.getCreatedAt().toEpochSecond(ZoneOffset.UTC);
            redisTemplate.opsForZSet().add(cityKey, savedPost.getId().toString(), timestampScore);
            redisTemplate.opsForValue().set("post:object:" + savedPost.getId(), savedPost, 24, java.util.concurrent.TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Redis feed caching bypassed: ", e);
        }

        return savedPost;
    }

    @Override
    public Page<FeedPostDTO> getCityFeed(String city, String category, Pageable pageable, Long currentUserId) {
        Page<Post> rawPosts;
        if (category != null && !category.isEmpty()) {
            rawPosts = postRepository.findByCityNameAndCategoryOrderByCreatedAtDesc(city, category, pageable);
        } else {
            rawPosts = postRepository.findByCityNameOrderByCreatedAtDesc(city, pageable);
        }

        return rawPosts.map(post -> {
            Map<String, String> authorProfile = profileCacheService.getUserProfileSummary(post.getUserId());

            // 🟢 FIXED: Actually check the DB sets to sync state with React Native
            String userVote = "NONE";
            if (post.getUpvotes() != null && post.getUpvotes().contains(currentUserId)) {
                userVote = "up";
            } else if (post.getDownvotes() != null && post.getDownvotes().contains(currentUserId)) {
                userVote = "down";
            }

            return FeedPostDTO.builder()
                    .id(post.getId())
                    .userId(post.getUserId())
                    .username(authorProfile.getOrDefault("username", "GhostTraveler"))
                    .avatarUrl(authorProfile.getOrDefault("avatarUrl", "/default.png"))
                    .isVerifiedResident("true".equals(authorProfile.getOrDefault("isVerifiedResident", "false")))
                    .title(post.getTitle())
                    .content(post.getContent())
                    .cityName(post.getCityName())
                    .mediaUrl(post.getMediaUrl())
                    .mediaType(post.getMediaType())
                    .score(post.getScore())
                    .commentCount(post.getCommentCount())
                    .currentUserVote(userVote) // Now perfectly synced!
                    .createdAt(post.getCreatedAt())
                    .build();
        });
    }
    @Override
    public Page<FeedPostDTO> getPersonalTimeline(Long targetUserId, Pageable pageable, Long currentUserId) {
        Page<Post> rawPosts = postRepository.findByUserIdOrderByCreatedAtDesc(targetUserId, pageable);

        return rawPosts.map(post -> {
            Map<String, String> authorProfile = profileCacheService.getUserProfileSummary(post.getUserId());

            // 🟢 FIXED: Lowercase and Null-Safe
            String userVote = "NONE";
            if (post.getUpvotes() != null && post.getUpvotes().contains(currentUserId)) {
                userVote = "up";
            } else if (post.getDownvotes() != null && post.getDownvotes().contains(currentUserId)) {
                userVote = "down";
            }

            return FeedPostDTO.builder()
                    .id(post.getId())
                    .userId(post.getUserId())
                    .username(authorProfile.getOrDefault("username", "GhostTraveler"))
                    .avatarUrl(authorProfile.getOrDefault("avatarUrl", "/default.png"))
                    .isVerifiedResident("true".equals(authorProfile.getOrDefault("isVerifiedResident", "false")))
                    .title(post.getTitle())
                    .content(post.getContent())
                    .cityName(post.getCityName())
                    .mediaUrl(post.getMediaUrl())
                    .mediaType(post.getMediaType())
                    .score(post.getScore())
                    .commentCount(post.getCommentCount())
                    .currentUserVote(userVote) // Perfectly synced!
                    .createdAt(post.getCreatedAt())
                    .build();
        });
    }

    @Override
    @Transactional
    public String votePost(Long postId, Long userId, String direction) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found."));

        post.getUpvotes().remove(userId);
        post.getDownvotes().remove(userId);

        if ("UP".equalsIgnoreCase(direction)) {
            post.getUpvotes().add(userId);
        } else if ("DOWN".equalsIgnoreCase(direction)) {
            post.getDownvotes().add(userId);
        }

        post.setScore(post.getUpvotes().size() - post.getDownvotes().size());
        postRepository.save(post);
        return "Vote registered. Score: " + post.getScore();
    }

    @Override
    @Transactional
    public Comment addComment(Comment comment, Long userId) {
        comment.setUserId(userId);
        Comment savedComment = commentRepository.save(comment);

        Post post = postRepository.findById(comment.getPostId())
                .orElseThrow(() -> new RuntimeException("Post not found for commenting."));

        post.setCommentCount(post.getCommentCount() + 1);
        postRepository.save(post);

        return savedComment;
    }




    @Override
    @Transactional
    public String castPeerVouch(Long voucherId, Long targetUserId, String location) {
        if (voucherId.equals(targetUserId)) throw new RuntimeException("Self-vouching invalid.");
        if (peerVouchRepository.existsByVoucherIdAndTargetUserId(voucherId, targetUserId)) {
            throw new RuntimeException("Already vouched for this user.");
        }

        PeerVouch vouch = PeerVouch.builder().voucherId(voucherId).targetUserId(targetUserId).locationContext(location).build();
        peerVouchRepository.save(vouch);

        long totalVouches = peerVouchRepository.countByTargetUserId(targetUserId);
        if (totalVouches >= 3) {
            profileCacheService.elevateUserToVerifiedResident(targetUserId);
            return "Vouch successful. User verified.";
        }
        return "Vouch recorded. Total: " + totalVouches + "/3";
    }

    @Override
    public Map<String, Object> getTrustNetworkStatus(Long userId) {
        long count = peerVouchRepository.countByTargetUserId(userId);
        Map<String, String> profile = profileCacheService.getUserProfileSummary(userId);
        return Map.of("vouchCount", count, "isVerifiedResident", profile.getOrDefault("isVerifiedResident", "false"));
    }



    @Override
    public void registerUserPublicKey(Long userId, String publicKeyBase64) {
        redisTemplate.opsForHash().put("user:public_keys", userId.toString(), publicKeyBase64);
    }

    @Override
    public String getUserPublicKey(Long userId) {
        return (String) redisTemplate.opsForHash().get("user:public_keys", userId.toString());
    }

    // ==========================================
    // 🔍 POLYMORPHIC CRIMINOLOGY MATRIX SEARCH
    // ==========================================
    @Override
    public Page<FeedPostDTO> searchThreatMatrix(String keyword, Pageable pageable, Long currentUserId) {
        Page<Post> rawPosts = postRepository.searchGlobalScams(keyword.trim(), pageable);

        return rawPosts.map(post -> {
            Map<String, String> authorProfile = profileCacheService.getUserProfileSummary(post.getUserId());

            // 🟢 FIXED: Lowercase and Null-Safe
            String userVote = "NONE";
            if (post.getUpvotes() != null && post.getUpvotes().contains(currentUserId)) {
                userVote = "up";
            } else if (post.getDownvotes() != null && post.getDownvotes().contains(currentUserId)) {
                userVote = "down";
            }

            return FeedPostDTO.builder()
                    .id(post.getId())
                    .userId(post.getUserId())
                    .username(authorProfile.getOrDefault("username", "GhostTraveler"))
                    .avatarUrl(authorProfile.getOrDefault("avatarUrl", "/default.png"))
                    .isVerifiedResident("true".equals(authorProfile.getOrDefault("isVerifiedResident", "false")))
                    .title(post.getTitle())
                    .content(post.getContent())
                    .cityName(post.getCityName())
                    .mediaUrl(post.getMediaUrl())
                    .mediaType(post.getMediaType())
                    .score(post.getScore())
                    .commentCount(post.getCommentCount())
                    .currentUserVote(userVote) // Perfectly synced!
                    .createdAt(post.getCreatedAt())
                    .build();
        });
    }

    @Override
    @Transactional
    public void incrementShareCount(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found."));
        post.setShareCount(post.getShareCount() + 1);
        postRepository.save(post);
    }

    @Override
    @Transactional
    public Comment addSecureComment(Long postId, Long userId, String content, Long parentId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Target post does not exist."));

        Comment comment = new Comment();
        comment.setPostId(post.getId());
        comment.setUserId(userId);
        comment.setContent(content.trim());
        comment.setParentId(parentId);
        comment.setCreatedAt(LocalDateTime.now());

        Comment savedComment = commentRepository.save(comment);

        post.setCommentCount(post.getCommentCount() + 1);
        postRepository.save(post);

        return savedComment;
    }
    // ==========================================
    // 💬 COMMENT FETCHING & NESTING LOGIC
    // ==========================================
    @Override
    public List<CommentResponseDTO> getPostComments(Long postId) {
        // 1. Fetch all raw comments for the post (Assuming you have this in CommentRepository)
        List<Comment> rawComments = commentRepository.findByPostIdOrderByCreatedAtAsc(postId);

        // 2. Map to DTO and enrich with User Profile Data
        List<CommentResponseDTO> mappedComments = rawComments.stream().map(comment -> {
            Map<String, String> authorProfile = profileCacheService.getUserProfileSummary(comment.getUserId());

            return CommentResponseDTO.builder()
                    .id(comment.getId())
                    .parentId(comment.getParentId())
                    .content(comment.getContent())
                    .username(authorProfile.getOrDefault("username", "Anonymous"))
                    .avatarUrl(authorProfile.getOrDefault("avatarUrl", null))
                    .createdAt(comment.getCreatedAt())
                    .build();
        }).collect(Collectors.toList());

        // 3. 🟢 NESTING ALGORITHM: Sort so children immediately follow their parents
        List<CommentResponseDTO> sortedNestingList = new ArrayList<>();

        for (CommentResponseDTO parent : mappedComments) {
            // If it's a top-level comment
            if (parent.getParentId() == null) {
                sortedNestingList.add(parent);

                // Immediately find and append any replies to this specific parent
                for (CommentResponseDTO child : mappedComments) {
                    if (parent.getId().equals(child.getParentId())) {
                        sortedNestingList.add(child);
                    }
                }
            }
        }

        return sortedNestingList;
    }

}