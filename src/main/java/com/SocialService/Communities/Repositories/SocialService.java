package com.SocialService.Communities.Repositories;

import com.SocialService.Communities.DTOs.CommentResponseDTO;
import com.SocialService.Communities.Models.Comment;
import com.SocialService.Communities.Models.Post;
import com.SocialService.Communities.DTOs.FeedPostDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

public interface SocialService {
    Post createPost(Post post, Long userId);
    Page<FeedPostDTO> getCityFeed(String city, String category, Pageable pageable, Long currentUserId);
    String votePost(Long postId, Long userId, String direction);

    Comment addComment(Comment comment, Long userId);
    Comment addSecureComment(Long postId, Long userId, String content, Long parentId);


    String castPeerVouch(Long voucherId, Long targetUserId, String location);
    Map<String, Object> getTrustNetworkStatus(Long userId);



    void registerUserPublicKey(Long userId, String publicKeyBase64);
    String getUserPublicKey(Long userId);

    Page<FeedPostDTO> searchThreatMatrix(String keyword, Pageable pageable, Long currentUserId);
    void incrementShareCount(Long postId);

    // 🟢 NEW: Dedicated method to fetch and map a user's personal timeline
    Page<FeedPostDTO> getPersonalTimeline(Long targetUserId, Pageable pageable, Long currentUserId);

    // ==========================================
    // 💬 COMMENT FETCHING & NESTING LOGIC
    // ==========================================
    List<CommentResponseDTO> getPostComments(Long postId);
}