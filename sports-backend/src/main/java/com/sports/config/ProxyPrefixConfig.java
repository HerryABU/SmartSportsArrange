package com.sports.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 反向代理子路径（帽子前缀）智能剥离过滤器
 *
 * 部署形态：应用可挂在任意反向代理子路径下，如 http://host/sportmg/，业务路径只能位于
 * 该「帽子」之后（如 /sportmg/login、/sportmg/api/xxx）。帽子（sportmg）仅仅是例子，
 * 本过滤器严禁硬编码任何具体前缀，而是智能判断是否剥离：
 *
 *   - 请求第一段之后剩余路径若是后端 API（/api 或 /api/**）→ 剥离帽子后交给 Controller；
 *   - 剩余路径若是静态资源（static 下真实存在的文件）→ 剥离帽子后正常返回；
 *   - 剩余路径是 SPA 前端路由（如 /sportmg/login）→ 不剥离，由 SPA 回退返回 index.html，
 *     前端会根据当前 URL 智能推断帽子并自适应（见 sports-frontend/src/utils/base.js）；
 *   - 无帽子部署（/login、/api/xxx）→ 原样放行，行为与改造前完全一致。
 *
 * 同时兼容两种反向代理形态：
 *   A. 保留帽子转发（proxy_pass http://backend;        → 收到 /sportmg/...）：本过滤器剥离；
 *   B. 剥掉帽子转发（proxy_pass http://backend/;       → 收到 /login）：无需剥离，直接放行。
 */
@Configuration
public class ProxyPrefixConfig {

    /** 智能帽子剥离过滤器（不作为 @Component，避免容器重复自动注册） */
    static class ProxyPrefixFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String uri = request.getRequestURI();
            if (uri == null || uri.length() <= 1) {
                filterChain.doFilter(request, response);
                return;
            }
            // 第一段结束位置（首个 / 之后的第二个 /），如 /sportmg/api/login → 下标 8
            int idx = uri.indexOf('/', 1);
            if (idx <= 0) {
                // 单段路径（/login、/favicon.svg 等）不可能是「帽子 + 业务路径」
                filterChain.doFilter(request, response);
                return;
            }
            String stripped = uri.substring(idx); // 去掉帽子后的剩余路径，以 / 开头
            if (stripped.length() <= 1) {
                // 形如 /sportmg/ —— 帽子 + 空路径，由 SPA 回退返回 index.html
                filterChain.doFilter(request, response);
                return;
            }
            boolean isApi = stripped.equals("/api") || stripped.startsWith("/api/");
            if (!isApi && !existsStaticResource(stripped)) {
                // 不是 API 也不是静态资源（即 SPA 前端路由，如 /sportmg/login），原样放行
                filterChain.doFilter(request, response);
                return;
            }
            // 智能剥离帽子前缀，后续 Filter 与 DispatcherServlet 均按干净路径处理
            filterChain.doFilter(new StrippedRequestWrapper(request, stripped), response);
        }

        private boolean existsStaticResource(String path) {
            try {
                Resource resource = new ClassPathResource("/static" + path);
                return resource.exists() && resource.isReadable();
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** 请求包装器：对外呈现剥离帽子后的干净路径 */
    static class StrippedRequestWrapper extends HttpServletRequestWrapper {

        private final String strippedPath;

        StrippedRequestWrapper(HttpServletRequest request, String strippedPath) {
            super(request);
            this.strippedPath = strippedPath;
        }

        @Override
        public String getRequestURI() {
            return strippedPath;
        }

        @Override
        public StringBuffer getRequestURL() {
            HttpServletRequest req = (HttpServletRequest) getRequest();
            StringBuffer url = new StringBuffer(req.getScheme()).append("://").append(req.getServerName());
            int port = req.getServerPort();
            if (port > 0 && port != 80 && port != 443) {
                url.append(':').append(port);
            }
            return url.append(strippedPath);
        }

        @Override
        public String getServletPath() {
            return strippedPath;
        }
    }

    /**
     * 注册过滤器并置于整个链路最前（早于 Spring Security 的 FilterChainProxy，order=-100），
     * 确保 Security 鉴权、JWT、DispatcherServlet 看到的都是剥离帽子后的干净路径。
     */
    @Bean
    public FilterRegistrationBean<ProxyPrefixFilter> proxyPrefixFilterRegistration() {
        FilterRegistrationBean<ProxyPrefixFilter> registration =
                new FilterRegistrationBean<>(new ProxyPrefixFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
