package com.SocialService.Communities.DTOs;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class PostRequest {
    private String mediaId;
    private String title;
    private String caption;
    private String cityName;
    private String category; // "GLOBAL_POST" or "LOCAL_RADAR"
    private MultipartFile file;
}