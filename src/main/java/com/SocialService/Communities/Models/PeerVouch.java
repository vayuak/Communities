package com.SocialService.Communities.Models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "peer_vouches", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"voucherId", "targetUserId"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeerVouch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long voucherId;    // The verified user who is signing the claim
    private Long targetUserId; // The user receiving the trust sign-off
    private String locationContext; // e.g., "Mumbai_Colaba"
    private LocalDateTime createdAt = LocalDateTime.now();
}