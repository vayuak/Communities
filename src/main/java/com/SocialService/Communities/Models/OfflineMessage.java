package com.SocialService.Communities.Models;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "offline_mailbox")
@Data
public class OfflineMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String senderUsername;

    @Column(nullable = false)
    private String recipientUsername;

    @Column(nullable = false)
    private String roomId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String encryptedPayload; // The AES-GCM blob

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();
    @Column(columnDefinition = "TEXT", nullable = false)
    private String ciphertext;

    @Column(nullable = false)
    private String iv;

    @Column(nullable = false)
    private String authTag;

    @Column(columnDefinition = "TEXT")
    private String ephemeralPublicKey;
}