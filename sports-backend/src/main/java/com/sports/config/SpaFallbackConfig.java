package com.sports.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA 路由回退配置 —— Vue Router history 模式需要所有非 API 路径返回 index.html
 */
@Configuration
public class SpaFallbackConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 先让 Spring Boot 处理 /api/** 等后端路由（由 Controller 处理），
        // 其他所有路径尝试匹配 static 下的文件，找不到就 fallback 到 index.html
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        // 如果请求的资源存在且不是目录，直接返回
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 否则回退到 index.html（SPA fallback）
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
