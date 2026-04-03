package com.zcyh.mr.springboot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * 全局跨域配置。
 * 生产环境应通过 MR_CORS_ALLOWED_ORIGINS 环境变量限制允许的跨域来源。
 */
@Configuration
public class CorsGlobalConfig implements WebMvcConfigurer {

    @Value("${mr.cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] originArray = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .toArray(String[]::new);
        if (originArray.length == 0) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(originArray)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
