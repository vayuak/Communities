package com.SocialService.Communities.Models;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "posts", indexes = {
        @Index(name = "idx_post_city", columnList = "city"),
        @Index(name = "idx_post_country", columnList = "country"),
        @Index(name = "idx_post_user", columnList = "username"),
        @Index(name = "idx_post_user_id", columnList = "user_id")
})
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class Post implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 50, name = "city_name")
    private String cityName;

    @Column(nullable = false, length = 20)
    private String title;

    @Column(nullable = false, length = 100, name = "content")
    private String content;

    @Column(name = "media_url")
    private String mediaUrl;

    @Column(name = "media_type")
    private String mediaType;

    @Column(nullable = false, length = 50)
    private String city;

    @Column(nullable = false, length = 50)
    private String country;

    @Column(nullable = false)
    @Builder.Default
    private String category = "GLOBAL_POST";

    @Column(name = "comment_count")
    private int commentCount = 0;

    @Column(name = "score")
    private int score = 0;

    @Column(name = "share_count")
    private int shareCount = 0; // 🟢 Added Share Tracking

    // 🟢 Added Relational Voting Sets
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "post_upvotes", joinColumns = @JoinColumn(name = "post_id"))
    @Column(name = "user_id")
    @Builder.Default
    private Set<Long> upvotes = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "post_downvotes", joinColumns = @JoinColumn(name = "post_id"))
    @Column(name = "user_id")
    @Builder.Default
    private Set<Long> downvotes = new HashSet<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}