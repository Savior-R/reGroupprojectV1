package com.shijiangtai.m1auth.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * JWT 签发（02 §2.1、03 §3.2）。
 *
 * <ul>
 *   <li>HS256；密钥来自环境变量，至少 256 bit
 *   <li>claims：{@code sub}（account_id）、{@code account_type}（主身份）、
 *       {@code roles}（授权角色集合，可多值）、{@code school_id}（可空）、
 *       {@code exp}、{@code iat}、{@code jti}
 *   <li>有效期 2 小时；不做 refresh token
 * </ul>
 *
 * <p>{@code account_type} 与 {@code roles} 并存是过渡期的刻意设计：
 * 前者表示“注册时确认的身份”，决定档案类型，M3 及前端既有代码仍在读它；
 * 后者表示“这次可以行使哪些角色”，是 RBAC 的判定依据。角色被授予或撤销后，
 * 需要重新登录才会在新 token 中体现。
 */
public class JwtTokenIssuer {

    /** HS256 要求 256 bit 密钥。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public JwtTokenIssuer(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT 密钥不能为空（M1_JWT_SECRET）");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT 密钥至少 " + MIN_SECRET_BYTES + " 字节（HS256 要求 256 bit）");
        }
        this.secret = keyBytes;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 只按主身份签发（单角色）。保留该重载给只关心身份的调用方与测试。
     *
     * @param schoolId 管理员未建校时为 {@code null}，此时不写入该 claim
     */
    public IssuedToken issue(AccountId accountId, AccountType type, SchoolId schoolId) {
        return issue(accountId, type, schoolId, Set.of(type));
    }

    /**
     * 签发 token。
     *
     * @param roles 授权角色集合，应至少包含主身份 {@code type}；多角色时按并集授权
     * @param schoolId 管理员未建校时为 {@code null}，此时不写入该 claim
     */
    public IssuedToken issue(
            AccountId accountId, AccountType type, SchoolId schoolId,
            Collection<AccountType> roles) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);

        Set<AccountType> effective = new TreeSet<>(roles == null ? Set.of() : roles);
        effective.add(type);

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(accountId.value().toString())
                .claim("account_type", type.name().toLowerCase(Locale.ROOT))
                .claim("roles", effective.stream()
                        .map(role -> role.name().toLowerCase(Locale.ROOT))
                        .toList())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString());
        if (schoolId != null) {
            claims.claim("school_id", schoolId.value().toString());
        }

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
        return new IssuedToken(jwt.serialize(), expiresAt);
    }

    /** 签发结果：序列化后的 token 与过期时刻。 */
    public record IssuedToken(String token, Instant expiresAt) {
    }
}
