package com.shijiangtai.shared.infrastructure.security;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 权限表注册表（02 §4.1.1、12 §3.1）。
 *
 * <p>把 RBAC 的三层数据加载到内存，供 {@link PermissionAuthorizationManager} 判定：
 * <ul>
 *   <li>{@code api_permissions}：{@code (method, path_pattern) → permission_code}
 *   <li>{@code account_type_permissions}：{@code account_type → Set<permission_code>}
 * </ul>
 *
 * <p>加载时机选 {@link ApplicationReadyEvent}：确保 Flyway 迁移已经执行完毕
 * （{@code V12} / {@code V13} 才有权限种子数据）。加载失败直接抛出，让启动失败——
 * 宁可起不来，也不要带着空表跑成「全部 403」。
 *
 * <p>规模假设：路由级权限在百级，路径匹配用「精确表 + 模板遍历」即可，
 * 不引入额外缓存依赖；表变更后调用 {@link #reload()} 或重启生效。
 */
@Component
public class PermissionRegistry {

    private static final Logger log = LoggerFactory.getLogger(PermissionRegistry.class);

    /** 路径里的 {@code {school_id}} 这类占位符。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^/}]+}");

    private final JdbcTemplate jdbc;

    /** 精确规则：{@code "GET /api/v1/auth/me"} → {@code login}。 */
    private volatile Map<String, String> exactRules = Map.of();

    /** 模板规则：路径含占位符，按「占位符少 → 多」排序，避免宽规则先命中。 */
    private volatile List<TemplateRule> templateRules = List.of();

    /** 角色 → 权限码集合；角色用小写 code（student / teacher / admin）。 */
    private volatile Map<String, Set<String>> rolePermissions = Map.of();

    private volatile boolean loaded;

    public PermissionRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 迁移完成后再加载。用 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
     * 后者可能在 Flyway 建表之前执行。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        reload();
    }

    public synchronized void reload() {
        Map<String, String> exact = new HashMap<>();
        List<TemplateRule> templates = new ArrayList<>();

        Integer total = jdbc.query("""
                select method, path_pattern, description
                from m1_auth.api_permissions
                """, rs -> {
                    int count = 0;
                    while (rs.next()) {
                        String method = rs.getString("method").toUpperCase(Locale.ROOT);
                        String pattern = rs.getString("path_pattern");
                        String code = rs.getString("description");
                        if (code == null || code.isBlank()) {
                            continue;
                        }
                        if (PLACEHOLDER.matcher(pattern).find()) {
                            templates.add(new TemplateRule(
                                    method, compile(pattern), code, placeholderCount(pattern)));
                        } else {
                            exact.put(method + " " + pattern, code);
                        }
                        count++;
                    }
                    return count;
                });

        templates.sort((a, b) -> Integer.compare(a.placeholders(), b.placeholders()));

        Map<String, Set<String>> roles = new HashMap<>();
        jdbc.query("""
                select at.code as role, p.description as code
                from m1_auth.account_type_permissions atp
                join m1_auth.account_types at on at.id = atp.account_type_id
                join m1_auth.api_permissions p on p.id = atp.permission_id
                """, rs -> {
                    String role = rs.getString("role").toLowerCase(Locale.ROOT);
                    String code = rs.getString("code");
                    if (code != null && !code.isBlank()) {
                        roles.computeIfAbsent(role, key -> new HashSet<>()).add(code);
                    }
                });

        Map<String, Set<String>> immutableRoles = new LinkedHashMap<>();
        roles.forEach((role, codes) -> immutableRoles.put(role, Set.copyOf(codes)));

        this.exactRules = Map.copyOf(exact);
        this.templateRules = List.copyOf(templates);
        this.rolePermissions = Map.copyOf(immutableRoles);
        this.loaded = true;

        log.info("权限表已加载：{} 条精确规则 / {} 条模板规则 / {} 个角色",
                exact.size(), templates.size(), immutableRoles.size());
        if (total != null && total == 0) {
            throw new IllegalStateException(
                    "api_permissions 为空，RBAC 无法工作；请确认 V12 / V13 迁移已执行");
        }
    }

    /**
     * 按 method + path 查权限码。
     *
     * <p>精确规则优先：{@code /api/v1/admin/schools} 不会被 {@code /api/v1/admin/{x}} 抢走。
     */
    public Optional<String> match(String method, String path) {
        if (!loaded) {
            return Optional.empty();
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        String exact = exactRules.get(normalizedMethod + " " + path);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (TemplateRule rule : templateRules) {
            if (rule.method().equals(normalizedMethod) && rule.pattern().matcher(path).matches()) {
                return Optional.of(rule.code());
            }
        }
        return Optional.empty();
    }

    public boolean roleHasPermission(String role, String permissionCode) {
        Set<String> codes = rolePermissions.get(role.toLowerCase(Locale.ROOT));
        return codes != null && codes.contains(permissionCode);
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** 诊断用：当前加载到的规则数。 */
    public Map<String, Integer> stats() {
        return Map.of(
                "exactRules", exactRules.size(),
                "templateRules", templateRules.size(),
                "roles", rolePermissions.size());
    }

    private static int placeholderCount(String pattern) {
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 把 {@code /a/{id}/b} 编译成 {@code ^\Q/a/\E[^/]+\Q/b\E$}。 */
    private static Pattern compile(String pathPattern) {
        StringBuilder regex = new StringBuilder("^");
        Matcher matcher = PLACEHOLDER.matcher(pathPattern);
        int last = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(pathPattern.substring(last, matcher.start())));
            regex.append("[^/]+");
            last = matcher.end();
        }
        regex.append(Pattern.quote(pathPattern.substring(last))).append('$');
        return Pattern.compile(regex.toString());
    }

    private record TemplateRule(String method, Pattern pattern, String code, int placeholders) {
    }
}
