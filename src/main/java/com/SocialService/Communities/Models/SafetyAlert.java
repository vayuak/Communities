package com.SocialService.Communities.Models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "safety_alerts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SafetyAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long reporterId;
    private String cityName;
    private String dangerSpot; // e.g., "Picnic Spot A Gate", "Main Railway Terminal"

    @Column(length = 1000)
    private String scamDescription; // Detailed warning of the betrayal or scam happening

    private String threatLevel; // LOW, MEDIUM, CRITICAL
    private LocalDateTime createdAt = LocalDateTime.now();
}