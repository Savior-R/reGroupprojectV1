package com.shijiangtai.shared.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT 认证与公开路径白名单（02 §2.1、03 §3.3）。
 *
 * <ul>
 *   <li>{@code /api/**} stateless，Bearer JWT，HS256 与 {@code JwtTokenIssuer} 同一密钥
 *   <li>公开路径精确列举；邮箱 / 微信绑定、解绑**不**通配放行（01 实现设计 §5 明确要求）
 *   <li>{@code account_type} 映射为 {@code ROLE_STUDENT / ROLE_TEACHER / ROLE_ADMIN}
 *   <li>URL 级权限由 {@link PermissionAuthorizationManager} 按 {@code api_permissions} 表判定；
 *       M1 的 {@code /api/v1/auth/**} 与 {@code /api/v1/admin/**} 已接入，
 *       M2—M7 路径暂用「登录即可」，待各自 owner 核对权限种子后再切换
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_POST = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/login/email",
            "/api/v1/auth/email/code",
            "/api/v1/auth/oauth/wechat/exchange",
            "/api/v1/auth/oauth/wechat/register",
    };

    private static final String[] PUBLIC_GET = {
            "/api/v1/auth/oauth/wechat/qr",
            "/api/v1/auth/oauth/wechat/callback",
            "/api/v1/auth/oauth/wechat/status",
            "/api/v1/public/**",
            "/actuator/health",
    };

    /**
     * 内部链路（02 §2.2）：只允许携带 {@code X-Internal-Token} 的调用，
     * 不经过 JWT 与 api_permissions；token 空则拒绝一切。
     *
     * <p>匹配范围收窄为 {@code /internal/auth/**}——Java 侧只对外提供
     * {@code /internal/auth/verify} 一个内部端点；{@code /internal/v1/*} 属于
     * Python AI 服务的路径，Java 在那些路径上只是客户端，不应拦截
     * （否则会挡住 IT 中模拟 Python 服务的 WS 处理器）。
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain internalSecurityFilterChain(
            HttpSecurity http, @Value("${m1.internal-token:}") String internalToken)
            throws Exception {
        http
                .securityMatcher("/internal/auth/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 令牌已由 InternalTokenFilter 校验，此处放行
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new InternalTokenFilter(internalToken),
                        org.springframework.security.web.authentication
                                .UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED", "内部令牌无效")));
        return http.build();
    }

    /**
     * WebSocket 握手（02 §2.3）：不走 auth_request，也不走 JWT 过滤器，
     * 由握手阶段自行 {@code GETDEL} 校验一次性 ticket，因此这里放行。
     *
     * <p>若缺少本链，{@code /ws/**} 会落进 Spring 默认安全链而被要求认证，
     * 表现为 websocket 握手 401。
     */
    @Bean
    @org.springframework.core.annotation.Order(3)
    public SecurityFilterChain webSocketSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/ws/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            PermissionAuthorizationManager permissionManager,
            org.springframework.beans.factory.ObjectProvider<TestHeaderAuthFilter> testFilter)
            throws Exception {
        http
                .securityMatcher("/api/**", "/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 公开白名单：不进 api_permissions（02 §4.1.1 明确要求）
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        // M1 范围启用表驱动授权（RBAC）
                        .requestMatchers("/api/v1/admin/**").access(permissionManager)
                        .requestMatchers("/api/v1/auth/**").access(permissionManager)
                        // M2—M7 路径暂用「登录即可」，待各自 owner 核对 api_permissions
                        // 后再逐个切到 access(permissionManager)，避免漏配导致误拒
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED", "未认证"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "无权限")));

        // test profile 才有该 bean：JWT 之后再兜底 X-Account-* 头，兼容 M3 既有集成测试
        TestHeaderAuthFilter headerFilter = testFilter.getIfAvailable();
        if (headerFilter != null) {
            http.addFilterAfter(
                    headerFilter,
                    org.springframework.security.oauth2.server.resource.web.authentication
                            .BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    /** 与 {@code JwtTokenIssuer} 共用同一环境变量密钥（02 §2.1）。 */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${m1.jwt.secret:}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * 角色 claim 映射为 {@code ROLE_*}。
     *
     * <p>多角色（V14）：JWT 的 {@code roles} 数组全部映射，如教师兼管理员
     * → {@code ROLE_TEACHER} + {@code ROLE_ADMIN}；旧 token 无 {@code roles}
     * 时退回 {@code account_type} 单值。权限列表不写进 token（03 §3.3）。
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            java.util.List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null || roles.isEmpty()) {
                String type = jwt.getClaimAsString("account_type");
                roles = type == null || type.isBlank() ? java.util.List.of() : java.util.List.of(type);
            }
            return roles.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> (org.springframework.security.core.GrantedAuthority)
                            new SimpleGrantedAuthority(
                                    "ROLE_" + value.toUpperCase(java.util.Locale.ROOT)))
                    .toList();
        });
        return converter;
    }

    private static void writeError(
            HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message
                        + "\",\"details\":{},\"trace_id\":null}");
    }
}
