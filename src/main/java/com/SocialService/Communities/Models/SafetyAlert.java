package com.SocialService.Communities.Models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
// 🛡️ THE SHIELD: One user can only report a specific post once.
@Table(name = "safety_alerts", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"reporterId", "targetPostId"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SafetyAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long reporterId;

    // 🟢 NEW: What exactly is being reported?
    private Long targetPostId;

    // 🟢 NEW: The calculated trust value of this report (0 = Bot/Scammer, 1 = Trusted)
    private Integer reportWeight = 0;

    private String cityName;
    private String dangerSpot;

    @Column(length = 1000)
    private String scamDescription;

    private String threatLevel; // LOW, MEDIUM, CRITICAL
    private LocalDateTime createdAt = LocalDateTime.now();
}