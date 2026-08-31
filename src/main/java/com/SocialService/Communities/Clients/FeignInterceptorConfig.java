package com.SocialService.Communities.Clients; // Update package if in a different service

import feign.RequestInterceptor;
import feign.codec.Encoder;
import feign.form.spring.SpringFormEncoder;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignInterceptorConfig {

    private static final String SHIELD_HEADER = "X-Ghost-Shield-Key";

    // 🟢 SECURE PRACTICE: Pulling from environment, no hardcoded fallbacks
    @Value("${ghost.shield.key}")
    private String shieldSecret;

    @Bean
    public RequestInterceptor gatewayShieldInterceptor() {
        // Automatically injects secure signatures into outbound microservice loops using the environment variable
        return requestTemplate -> requestTemplate.header(SHIELD_HEADER, shieldSecret);
    }

    @Bean
    public Encoder feignFormEncoder(ObjectFactory<HttpMessageConverters> messageConverters) {
        // Enables multipart/form-data encoding for cross-service file transfers
        return new SpringFormEncoder(new SpringEncoder(messageConverters));
    }
}