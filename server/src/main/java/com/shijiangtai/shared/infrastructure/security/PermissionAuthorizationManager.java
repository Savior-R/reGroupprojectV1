package com.shijiangtai.shared.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * 表驱动的接口授权（RBAC，02 §4.1.1、FR-M1-07）。
 *
 * <p>判定顺序：
 * <ol>
 *   <li>未认证 → 拒绝（由 ExceptionTranslationFilter 转 401）
 *   <li>从 JWT claim {@code account_type}（或 {@code ROLE_*} authority）解析角色
 *   <li>按 {@code (method, path)} 在 {@code api_permissions} 中查权限码
 *   <li>查该角色是否拥有该权限码 → 放行 / 403
 * </ol>
 *
 * <p><b>fail closed</b>：路径在表里找不到时一律拒绝，并打 WARN 日志。
 * 这是刻意选择——漏配规则必须暴露出来，不能默认放行。
 *
 * <p>注意：数据级权限（跨校访问）不在这里判断，仍由
 * {@code OrgApplicationService} / {@code TeacherMajorService} 的
 * {@code CROSS_SCHOOL_ACCESS} 校验负责。RBAC 管「能不能调这个接口」，
 * 数据范围管「能操作哪些行」，两者互补。
 */
@Component
public class PermissionAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Logger log = LoggerFactory.getLogger(PermissionAuthorizationManager.class);

    private final PermissionRegistry registry;

    public PermissionAuthorizationManager(PermissionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AuthorizationDecision authorize(
            Supplier<? extends Authentication> authenticationSupplier,
            RequestAuthorizationContext context) {
        Authentication authentication = authenticationSupplier.get();

        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        HttpServletRequest request = context.getRequest();
        String method = request.getMethod();
        String path = resolvePath(request);

        String role = resolveRole(authentication);
        if (role == null) {
            log.warn("无法从认证信息解析账号类型，拒绝 method={} path={}", method, path);
            return new AuthorizationDecision(false);
        }

        Optional<String> code = registry.match(method, path);
        if (code.isEmpty()) {
            log.warn("api_permissions 缺少该路由的规则，拒绝 method={} path={} role={}",
                    method, path, role);
            return new AuthorizationDecision(false);
        }

        boolean allowed = registry.roleHasPermission(role, code.get());
        if (!allowed) {
            log.info("权限不足，拒绝 role={} permission={} method={} path={}",
                    role, code.get(), method, path);
        }
        return new AuthorizationDecision(allowed);
    }

    private static String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /** JWT 优先取 claim；dev / 测试的 header 身份退化为按 {@code ROLE_*} 反推。 */
    private static String resolveRole(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String type = jwt.getClaimAsString("account_type");
            if (type != null && !type.isBlank()) {
                return type.toLowerCase(Locale.ROOT);
            }
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (value.startsWith("ROLE_")) {
                return value.substring("ROLE_".length()).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }
}
