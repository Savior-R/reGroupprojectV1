package com.shijiangtai.shared.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 给公开数据接口加浏览器缓存头（{@code /api/v1/public/**}）。
 *
 * <p>这些数据（学校 / 专业 / 年级 / 班级）变更频率低，允许缓存 60 秒：
 * 正常用户来回切换注册选项时不再重复打到后端，被脚本反复拉取时也能削减数据库压力。
 *
 * <p>注意：缓存对脚本无效（脚本会忽略 Cache-Control），真正挡批量抓取的是
 * Nginx 的 {@code public_limit}（30r/m）。两者互补，不是替代关系。
 */
@Component
@Order(0)
public class PublicCacheHeaderFilter extends OncePerRequestFilter {

    private static final String PUBLIC_PREFIX = "/api/v1/public/";
    private static final String CACHE_CONTROL = "public, max-age=60";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return !uri.startsWith(PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            response.setHeader("Cache-Control", CACHE_CONTROL);
        }
        filterChain.doFilter(request, response);
    }
}
