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
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * JWT 签发（02 §2.1、03 §3.2）。
 *
 * <ul>
 *   <li>HS256；密钥来自环境变量，至少 256 bit
 *   <li>claims：{@code sub}（account_id）、{@code account_type}、{@code school_id}（可空）、
 *       {@code exp}、{@code iat}、{@code jti}
 *   <li>有效期 2 小时；不做 refresh token
 * </ul>
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
     * 签发 token。
     *
     * @param schoolId 管理员未建校时为 {@code null}，此时不写入该 claim
     */
    public IssuedToken issue(AccountId accountId, AccountType type, SchoolId schoolId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(accountId.value().toString())
                .claim("account_type", type.name().toLowerCase(Locale.ROOT))
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
