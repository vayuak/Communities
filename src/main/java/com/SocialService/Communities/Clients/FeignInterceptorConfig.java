package com.SocialService.Communities.Clients;

import feign.RequestInterceptor;
import feign.codec.Encoder;
import feign.form.spring.SpringFormEncoder;

// 🟢 FIX: Added missing ObjectFactory import
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringEncoder;
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

    @Bean
    public Encoder feignFormEncoder(ObjectFactory<HttpMessageConverters> messageConverters) {
        // Enables multipart/form-data encoding for cross-service file transfers
        return new SpringFormEncoder(new SpringEncoder(messageConverters));
    }
}