package com.SocialService.Communities.Clients;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignInterceptorConfig {

    private static final String SHIELD_HEADER = "X-Ghost-Shield-Key";
    private static final String SHIELD_SECRET = "PermanentSecret999";

    @Bean
    public RequestInterceptor gatewayShieldInterceptor() {
        // Automatically injects security signatures into outbound microservice loops
        return requestTemplate -> requestTemplate.header(SHIELD_HEADER, SHIELD_SECRET);
    }
}