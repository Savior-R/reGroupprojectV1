package com.shijiangtai.shared.infrastructure.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部认证端点（02 §2.2）：Nginx {@code /_auth} 子请求调用，验证 Bearer JWT 并回传身份头。
 *
 * <p>本端点由 {@link InternalTokenFilter} 保护，不经过 {@code api_permissions}；
 * 只做验签与过期判断，角色 / 资源级权限仍在各自服务内完成。
 */
@RestController
@RequestMapping("/internal")
public class InternalAuthController {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;

    public InternalAuthController(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * @return 200 + {@code X-Account-Id} / {@code X-Account-Type}（主身份）/
     *     {@code X-Account-Roles}（逗号分隔的授权角色）/ {@code X-School-Id}；
     *     缺少或无效 token 返回 401，由 Nginx 转成对客户端的 401
     */
    @GetMapping("/auth/verify")
    public ResponseEntity<Void> verify(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return ResponseEntity.status(401).build();
        }
        try {
            Jwt jwt = jwtDecoder.decode(authorization.substring(BEARER.length()));
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Account-Id", jwt.getSubject());
            String accountType = jwt.getClaimAsString("account_type");
            if (accountType != null) {
                headers.set("X-Account-Type", accountType);
            }
            // 多角色（V14）：下游按角色集合判定，不能只依赖主身份
            java.util.List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null && !roles.isEmpty()) {
                headers.set("X-Account-Roles", String.join(",", roles));
            }
            String schoolId = jwt.getClaimAsString("school_id");
            if (schoolId != null && !schoolId.isBlank()) {
                headers.set("X-School-Id", schoolId);
            }
            return ResponseEntity.ok().headers(headers).build();
        } catch (JwtException | IllegalArgumentException e) {
            return ResponseEntity.status(401).build();
        }
    }
}
