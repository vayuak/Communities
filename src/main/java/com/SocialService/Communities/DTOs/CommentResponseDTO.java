package com.SocialService.Communities.DTOs;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class CommentResponseDTO {
    private Long id;
    private Long parentId;
    private String content;

    // 👤 Attached dynamically from Profile Cache
    private String username;
    private String avatarUrl;

    private LocalDateTime createdAt;
}