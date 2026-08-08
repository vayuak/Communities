package com.SocialService.Communities.DTOs;

import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationEvent {
    private String type;        // e.g., "CRITICAL_SCAM", "TRUST_ELEVATION", "GOVERNANCE_UPDATE"
    private String title;       // Headline of the alert
    private String message;     // Body text of the notification
    private String cityName;    // Targeted geo-location if applicable
    private LocalDateTime timestamp;

}