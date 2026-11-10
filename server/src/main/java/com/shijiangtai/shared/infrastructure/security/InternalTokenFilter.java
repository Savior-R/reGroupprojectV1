package com.shijiangtai.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部服务令牌校验（02 §2.2、03 §3.3）：{@code /internal/**} 只接受携带
 * {@code X-Internal-Token} 的调用（由 Nginx 的 {@code /_auth} 或内部服务注入）。
 *
 * <p>配置为空时**拒绝所有**（fail closed），避免漏配导致内部接口裸奔。
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final byte[] expected;

    public InternalTokenFilter(String expectedToken) {
        this.expected = expectedToken == null || expectedToken.isBlank()
                ? null : expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String actual = request.getHeader(HEADER);
        if (expected == null || actual == null
                || !MessageDigest.isEqual(
                        expected, actual.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("Content-Type", "application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":\"UNAUTHORIZED\",\"message\":\"内部令牌无效\",\"details\":{}}");
            return;
        }
        chain.doFilter(request, response);
    }
}
