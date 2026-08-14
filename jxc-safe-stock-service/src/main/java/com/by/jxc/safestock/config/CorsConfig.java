package com.by.jxc.safestock.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置：前后端分离，从配置 app.cors.allowed-origins 读取允许的前端来源（逗号分隔）。
 * 收紧来源白名单，避免开放任意 origin。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
