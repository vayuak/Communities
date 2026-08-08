package com.SocialService.Communities.DTOs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserDTO {
    private Long id; // Scaled cleanly to matches target Long types
    private String username;
    private String email;
    private boolean isPremium;
}