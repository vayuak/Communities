package com.SocialService.Communities;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableFeignClients
@SpringBootApplication(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@EnableScheduling
public class CommunitiesApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommunitiesApplication.class, args);
        log.info("   COMMUNITIES-SERVICE ready for frontend connection layer !");

    }
}