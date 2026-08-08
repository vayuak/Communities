package com.SocialService.Communities.DTOs;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class FeedPostDTO {
    private Long id;

    // 👤 Profile Data (Mapped via Cache)
    private Long userId;
    private String username;
    private String avatarUrl;
    private boolean isVerifiedResident;

    // 📝 Post Data
    private String title;
    private String content;
    private String cityName;
    private String mediaUrl;
    private String mediaType;

    // 📊 Metrics (Votes & Comments)
    private Integer score;
    private int commentCount;
    private String currentUserVote; // "UP", "DOWN", or "NONE"

    private LocalDateTime createdAt;
}