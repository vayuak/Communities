package com.SocialService.Communities.Clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

// 🟢 Strict enforcement: Requires properties to be set
@FeignClient(
        name = "${user.catalog.service.name}",
        url = "${user.catalog.service.url}",
        path = "/api/users",
        configuration = FeignInterceptorConfig.class)
public interface UserCatalogClient {
    @GetMapping("/internal/search-owners")
    List<Map<String, Object>> searchUsersByHandle(@RequestParam("username") String username);

    @PutMapping("/internal/profile/update-avatar")
    void updateInternalAvatar(@RequestParam("username") String username, @RequestBody Map<String, String> payload);
}