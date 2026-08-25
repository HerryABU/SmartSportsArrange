package com.sports.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * SPA 路由回退配置 —— Vue Router history 模式需要所有非 API 路径返回 index.html
 *
 * 缓存策略（防"升级后浏览器/反代仍用旧壳"）：
 * - /assets/**：文件名带内容哈希，可长期缓存（1 年），升级后文件名自动变化
 * - 其余路径（index.html / SPA 回退）：no-store，每次取最新 SPA 壳
 */
@Configuration
public class SpaFallbackConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 带哈希的静态资源：长期缓存
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());

        // 其他路径：匹配 static 文件，找不到则回退 index.html；全部 no-store
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore())
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
