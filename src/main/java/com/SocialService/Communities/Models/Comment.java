/*package com.SocialService.Communities.Models;



import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "comments", indexes = {
        @Index(name = "idx_post_comments", columnList = "post_id"),
        @Index(name = "idx_parent_node", columnList = "parent_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "parent_id", nullable = false)
    private String parentId; // Can be "post:{id}" or "comment:{id}"

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 1000)
    private String content;

    private int score;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.score = 0;
    }
}*/
package com.SocialService.Communities.Models;

import lombok.Data;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "comments", indexes = {
        @Index(name = "idx_post_id", columnList = "postId"),
        @Index(name = "idx_parent_id", columnList = "parentId")
})
@Data
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long postId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 1000)
    private String content;

    // NULLABLE: If null, this is a top-level root comment. If present, it's a reply!
    private Long parentId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}