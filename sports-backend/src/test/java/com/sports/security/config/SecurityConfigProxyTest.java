package com.sports.security.config;

import com.sports.security.jwt.JwtAuthenticationFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反向代理（cpolar / nginx SSL）场景下的安全配置回归测试。
 *
 * <p>纯单测，不启 Spring 上下文：只钉「代理下必须开的两条配置」没被人改回去。
 *
 * <p>背景：本系统前端产物带 {@code crossorigin} 的 module script，浏览器会给<strong>同源</strong>的
 * JS/CSS 请求也带上 {@code Origin} 头。直连时 Origin 与后端所见地址天然同源，CorsFilter 直接放行；
 * 一旦挂到 {@code https://xxx.cpolar.cn} 后面，Origin 是 https、后端自认 http://host:8080
 * （除非开了 forward-headers），两者被判跨域 → 403 → index.html 能回来但 assets 全 403 → 整页白屏。
 * 接口本身不受影响，所以现象常被误判成「前端坏了」。
 *
 * <p>于是这里钉死两件事：① SecurityConfig 必须给出显式 CORS 配置（而不是空的
 * {@code .cors(cors -> {})}）；② application.yml 必须开 {@code forward-headers-strategy}。
 */
class SecurityConfigProxyTest {

    /** 任意 cpolar 随机子域都该放行，所以允许源只能用 pattern 而非写死域名。 */
    private static final String PROXY_ORIGIN = "https://abc123.rpc.cpolar.top";

    private final SecurityConfig config = new SecurityConfig((JwtAuthenticationFilter) null);

    private final CorsConfigurationSourceHolder holder =
            new CorsConfigurationSourceHolder(config);

    private CorsConfiguration corsFor(String path) {
        CorsConfiguration cfg = holder.of(path);
        assertNotNull(cfg, "CORS 配置不能为空（等于 .cors(cors -> {}) 的空配置，代理下必 403）");
        return cfg;
    }

    @Test
    @DisplayName("CORS 覆盖 /**，且允许 https 代理来源（pattern * 而非写死域名）")
    void allowsProxyOriginViaPattern() {
        CorsConfiguration cfg = corsFor("/**");
        assertNotNull(cfg.checkOrigin(PROXY_ORIGIN),
                "cpolar 来源被拒：" + PROXY_ORIGIN + " —— 反向代理下会整页白屏");
        // 换成自有域名 / 局域网 http 调试地址也该通
        assertNotNull(cfg.checkOrigin("https://sports.example.com"));
        assertNotNull(cfg.checkOrigin("http://192.168.1.10:8080"));
    }

    @Test
    @DisplayName("暴露 Authorization：登录把 JWT 放响应头里返回，前端必须能读到")
    void exposesAuthorizationHeader() {
        List<String> exposed = corsFor("/**").getExposedHeaders();
        assertTrue(exposed.contains("Authorization"),
                "未暴露 Authorization，登录响应头里的 JWT 读不到 → 一登录就被踢回登录页：" + exposed);
    }

    @Test
    @DisplayName("带 credentials 的配置集必须通过 Spring 校验（allowCredentials + pattern 组合）")
    void credentialsConfigurationIsValid() {
        CorsConfiguration cfg = corsFor("/**");
        assertTrue(cfg.getAllowCredentials(), "代理下带凭据的请求会被拒");
        assertDoesNotThrow(cfg::validateAllowCredentials,
                "allowCredentials 与允许源的组合非法，启动期就会抛");
    }

    @Test
    @DisplayName("application.yml 开启 forward-headers-strategy：否则后端永远自认 http://host:8080")
    void applicationDeclaresForwardHeadersStrategy() throws Exception {
        Path yml = Path.of("src", "main", "resources", "application.yml");
        assertTrue(Files.exists(yml), "找不到 " + yml);
        String text = Files.readString(yml);
        assertTrue(text.contains("forward-headers-strategy"),
                "application.yml 缺少 server.forward-headers-strategy，"
                        + "Spring 不识别 X-Forwarded-*，代理下 Origin(scheme=https) 与后端自认(http) 判跨域 → 白屏");
        assertTrue(text.lines().anyMatch(l -> l.trim().startsWith("forward-headers-strategy:")),
                "forward-headers-strategy 必须写在 server 块下");
    }

    @Test
    @DisplayName("源码里不再留 .cors(cors -> {}) 空配置（回归钉子）")
    void sourceHasNoEmptyCorsConfig() throws Exception {
        Path p = Path.of("src", "main", "java", "com", "sports", "security", "config", "SecurityConfig.java");
        assertTrue(Files.exists(p), "找不到 " + p);
        assertTrue(Files.readString(p).contains("corsConfigurationSource()"),
                "SecurityConfig 又退回空 CORS 配置了（代理下 assets 会 403 白屏）");
    }

    /** 只借 corsConfigurationSource() 拿注册好的配置，避免为了读一行配置拉起整个 HttpSecurity。 */
    private static final class CorsConfigurationSourceHolder {
        private final UrlBasedCorsConfigurationSource source;

        CorsConfigurationSourceHolder(SecurityConfig config) {
            this.source = (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        }

        CorsConfiguration of(String path) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            return source.getCorsConfiguration(req);
        }
    }
}
