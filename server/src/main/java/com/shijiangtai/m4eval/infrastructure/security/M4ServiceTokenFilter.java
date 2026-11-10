package com.shijiangtai.m4eval.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates {@code X-Service-Token} on the M4 internal API called by the Python worker.
 *
 * <p>Fail closed: an empty configured token rejects every request. The shared foundation filter
 * uses {@code X-Internal-Token} and only matches {@code /internal/auth/**}, hence this M4-local
 * filter rather than an edit to the shared security skeleton.
 */
public class M4ServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Token";

    private final byte[] expected;

    public M4ServiceTokenFilter(String expectedToken) {
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
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":\"UNAUTHORIZED\",\"message\":\"服务令牌无效\",\"details\":{}}");
            return;
        }
        chain.doFilter(request, response);
    }
}
