package com.sports.config;

import com.sports.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ===== 公开访问（无需认证）=====
                        // 静态资源 + 前端 SPA 路由
                        .requestMatchers("/", "/index.html", "/favicon.svg", "/icons.svg").permitAll()
                        .requestMatchers("/assets/**").permitAll()
                        .requestMatchers("/login", "/teacher/**", "/class-teacher/**", "/student/**").permitAll()
                        // 任意 GET 且非 API 的路径放行 —— 兼容反向代理子路径部署（如 /sportmg/login 帽子+SPA 路由）
                        // API 均以 /api 开头，不在此放行范围，仍由下方规则按角色鉴权
                        .requestMatchers(req -> {
                            if (!"GET".equalsIgnoreCase(req.getMethod())) return false;
                            String p = req.getRequestURI();
                            if (p.startsWith("/api/")) return false;
                            if (p.startsWith("/actuator")) return false;
                            return true;
                        }).permitAll()

                        // 公开 API
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/refresh").permitAll()
                        .requestMatchers("/api/system/health").permitAll()
                        // 建站向导（安装后由业务层锁定）
                        .requestMatchers("/api/setup/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/api-docs/**").permitAll()

                        // 模板下载 - 无需认证
                        .requestMatchers(HttpMethod.GET, "/api/athletes/template").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/system/users/template").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/excel/template/**").permitAll()

                        // ===== 认证用户公共权限（登录即可）=====
                        .requestMatchers("/api/auth/profile").authenticated()
                        .requestMatchers("/api/auth/change-password").authenticated()

                        // ===== 学生端 =====
                        .requestMatchers(HttpMethod.GET, "/api/student/**").hasAnyAuthority("ROLE_STUDENT", "ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/events/**").hasAnyAuthority("ROLE_STUDENT", "ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/arrange/**").hasAnyAuthority("ROLE_STUDENT", "ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/results/**").hasAnyAuthority("ROLE_STUDENT", "ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/ranking/**").hasAnyAuthority("ROLE_STUDENT", "ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")

                        // ===== 班主任端 =====
                        .requestMatchers("/api/class-teacher/**").hasAnyAuthority("ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/registrations/**").hasAnyAuthority("ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/athletes/**").hasAnyAuthority("ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/classes/**").hasAnyAuthority("ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/statistics/**").hasAnyAuthority("ROLE_CLASS_TEACHER", "ROLE_TEACHER", "ROLE_SUPER_ADMIN")

                        // ===== 体育老师端 - 管理 =====
                        .requestMatchers("/api/classes/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/athletes/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/events/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/arrange/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/results/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/ranking/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/statistics/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/excel/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")

                        // ===== 体育老师可访问的系统配置 =====
                        .requestMatchers("/api/system/config/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/system/grades/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_SUPER_ADMIN")

                        // ===== 超级管理员独有（用户管理 / 数据库迁移 / 备份） =====
                        .requestMatchers("/api/system/**").hasAuthority("ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/db-migration/**").hasAuthority("ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/backup/**").hasAuthority("ROLE_SUPER_ADMIN")

                        // 其他所有请求需要认证
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
