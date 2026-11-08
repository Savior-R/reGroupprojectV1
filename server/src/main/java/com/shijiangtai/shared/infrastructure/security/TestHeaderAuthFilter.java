package com.shijiangtai.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 测试 / 本地开发专用的身份兜底：当请求没有通过 JWT 认证、但带 {@code X-Account-Id} /
 * {@code X-Account-Type}（M3 既有页面与测试的写法）时，构造一个已认证的主体。
 *
 * <p><b>仅 {@code test} 与 {@code dev} profile 生效</b>，生产（默认 / docker）不会注册该 bean，
 * 因此不存在伪造请求头的风险。新代码请使用 Bearer JWT。
 *
 * <p>⚠️ 若把 {@code dev} profile 部署到公网，本类会变成身份伪造入口，切勿这样部署。
 *
 * <p><b>归属说明</b>：本类位于 {@code shared}（Foundation），但服务对象是 M3 既有页面与测试
 * （{@code X-Account-*} 头）。M3 Owner 当前**待团队确认**，追认前应视为临时兼容层；
 * M3 全站切换到 Bearer JWT 后应删除本类。
 */
@Component
@Profile({"test", "dev"})
public class TestHeaderAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String accountId = request.getHeader("X-Account-Id");
            String accountType = request.getHeader("X-Account-Type");
            if (accountId != null && !accountId.isBlank()
                    && accountType != null && !accountType.isBlank()) {
                try {
                    String schoolId = request.getHeader("X-School-Id");
                    HeaderIdentity identity = new HeaderIdentity(
                            UUID.fromString(accountId),
                            accountType.toUpperCase(Locale.ROOT),
                            schoolId == null || schoolId.isBlank()
                                    ? null : UUID.fromString(schoolId));
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    identity,
                                    null,
                                    List.of(new SimpleGrantedAuthority(
                                            "ROLE_" + identity.accountType())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (IllegalArgumentException ignored) {
                    // 头部非法则保持未认证，交由入口点返回 401
                }
            }
        }
        chain.doFilter(request, response);
    }
}
