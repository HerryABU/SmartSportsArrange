package com.sports.middleware.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CORS 跨域配置
 *
 * <p>默认放行本地开发来源（localhost / 127.0.0.1 / 0.0.0.0 任意端口）。生产部署或 cpolar/ngrok 等隧道场景，
 * 可通过配置项 {@code sports.cors.allowed-origins}（逗号分隔、支持通配符）追加允许的前端来源，
 * 例如 {@code sports.cors.allowed-origins=https://meet.example.com,https://*.ngrok.io}。</p>
 */
@Configuration
public class CorsConfig {

    /** 额外允许的来源（生产/隧道场景）：逗号分隔，支持通配符；缺省为空 */
    @Value("${sports.cors.allowed-origins:}")
    private String extraOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 默认允许的开发/本地来源
        List<String> patterns = new ArrayList<>(List.of(
            "http://localhost:*",      // 前端开发服务器
            "http://127.0.0.1:*",     // 本地访问
            "http://0.0.0.0:*"        // 所有本地接口
        ));
        // M9 修复：追加可配置的生产/隧道来源
        if (extraOrigins != null && !extraOrigins.isBlank()) {
            for (String o : extraOrigins.split(",")) {
                String t = o.trim();
                if (!t.isEmpty()) patterns.add(t);
            }
        }
        config.setAllowedOriginPatterns(patterns);
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
