package com.SocialService.Communities;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableCaching
@EnableDiscoveryClient
@EnableFeignClients
@SpringBootApplication
@EnableScheduling
public class CommunitiesApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommunitiesApplication.class, args);
        log.info("   COMMUNITIES-SERVICE ready for frontend connection layer !");
    }
}