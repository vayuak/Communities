package com.SocialService.Communities.DTOs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CommentRequestDTO {

    @NotBlank(message = "Comment cannot be empty")
    @Size(max = 500, message = "Comment exceeds maximum length of 500 characters")
    private String content;

    // Nullable: Only used if they are replying to another comment
    private Long parentId;
}