package com.SocialService.Communities.Clients;

import com.SocialService.Communities.DTOs.UserDTO;
import com.SocialService.Communities.Clients.FeignInterceptorConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "USER-CATALOG-SERVICE", configuration = FeignInterceptorConfig.class)
public interface UserClient {

    @GetMapping("/api/users/internal/{id}")
    UserDTO getInternalUser(@PathVariable("id") Long id);
}