package com.shijiangtai.shared.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
 *   <li>从 JWT claim {@code roles}（数组）解析角色集合；旧 token 无该 claim 时
 *       退化为 {@code account_type} 单值，再退化为 {@code ROLE_*} authority
 *   <li>按 {@code (method, path)} 在 {@code api_permissions} 中查权限码
 *   <li>角色集合里<b>任一角色</b>拥有该权限码 → 放行 / 403
 * </ol>
 *
 * <p>取并集是多角色的关键：教师被授予 admin 后同时拥有两组权限，
 * 不需要放弃教师功能。撤销后重新登录即失去该组权限。
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

        Set<String> roles = resolveRoles(authentication);
        if (roles.isEmpty()) {
            log.warn("无法从认证信息解析角色，拒绝 method={} path={}", method, path);
            return new AuthorizationDecision(false);
        }

        Optional<String> code = registry.match(method, path);
        if (code.isEmpty()) {
            log.warn("api_permissions 缺少该路由的规则，拒绝 method={} path={} roles={}",
                    method, path, roles);
            return new AuthorizationDecision(false);
        }

        boolean allowed = roles.stream()
                .anyMatch(role -> registry.roleHasPermission(role, code.get()));
        if (!allowed) {
            log.info("权限不足，拒绝 roles={} permission={} method={} path={}",
                    roles, code.get(), method, path);
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

    /**
     * 解析角色集合。优先级：JWT {@code roles} → JWT {@code account_type} → {@code ROLE_*}。
     * 后两级用于兼容多角色上线前签发的旧 token 与 dev / 测试的头身份。
     */
    private static Set<String> resolveRoles(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            List<String> claim = jwt.getClaimAsStringList("roles");
            if (claim != null && !claim.isEmpty()) {
                return claim.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
            String type = jwt.getClaimAsString("account_type");
            if (type != null && !type.isBlank()) {
                return Set.of(type.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> fromAuthorities = new java.util.HashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (value.startsWith("ROLE_")) {
                fromAuthorities.add(value.substring("ROLE_".length()).toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(fromAuthorities);
    }
}
