package com.SocialService.Communities.Configurations;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

@Configuration
// 🟢 MVP 3.0-PAGE SERIALIZATION FIX: Forces standard JSON DTO serialization on PageImpl returns
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class SpringDataWebConfig {
}