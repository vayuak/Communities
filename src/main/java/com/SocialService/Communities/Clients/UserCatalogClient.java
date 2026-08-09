package com.SocialService.Communities.Clients;

import com.SocialService.Communities.DTOs.ProfileUpdateRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import java.util.Map;

@FeignClient(name = "USER-CATALOG-SERVICE",url = "${user.catalog.service.url}", path = "/api/users", configuration = FeignInterceptorConfig.class)
public interface UserCatalogClient {

    @GetMapping("/internal/search-owners")
    List<Map<String, Object>> searchUsersByHandle(@RequestParam("username") String username);

    // 👈 NEW: Secure internal profile update route
    @PutMapping("/internal/profile/update-avatar")
    void updateInternalAvatar(@RequestParam("username") String username, @RequestBody Map<String, String> payload);
}