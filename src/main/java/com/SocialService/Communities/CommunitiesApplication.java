package com.SocialService.Communities;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@EnableFeignClients(basePackages = "com.SocialService.Communities.FeignClients")
@SpringBootApplication
@EnableScheduling
public class CommunitiesApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommunitiesApplication.class, args);
    }
}
