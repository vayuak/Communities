package com.SocialService.Communities.Models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "comment_reports", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"reporterId", "targetCommentId"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long reporterId;
    private Long targetCommentId;

    @Column(length = 500)
    private String reason;

    private LocalDateTime createdAt = LocalDateTime.now();
}