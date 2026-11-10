package com.shijiangtai.m1auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * JWT 签发（02 §2.1、03 §3.2）：HS256、2 小时、claims = sub/account_type/school_id/exp/iat/jti。
 */
class JwtTokenIssuerTest {

    private static final String SECRET = "unit-test-secret-32-bytes-minimum-000000";
    private static final Instant NOW = Instant.parse("2026-11-08T02:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private final AccountId accountId = new AccountId(UUID.randomUUID());
    private final SchoolId schoolId = new SchoolId(UUID.randomUUID());

    private JwtTokenIssuer issuer() {
        return new JwtTokenIssuer(SECRET, Duration.ofHours(2), FIXED);
    }

    private JWTClaimsSet parse(String token) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
        return jwt.getJWTClaimsSet();
    }

    @Test
    void issuesVerifiableHs256TokenWithExpectedClaims() throws Exception {
        String token = issuer().issue(accountId, AccountType.STUDENT, schoolId).token();

        JWTClaimsSet claims = parse(token);
        assertThat(claims.getSubject()).isEqualTo(accountId.value().toString());
        assertThat(claims.getStringClaim("account_type")).isEqualTo("student");
        assertThat(claims.getStringClaim("school_id")).isEqualTo(schoolId.value().toString());
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getJWTID()).isNotBlank();
    }

    @Test
    void expiresAfterTwoHours() throws Exception {
        String token = issuer().issue(accountId, AccountType.TEACHER, schoolId).token();

        JWTClaimsSet claims = parse(token);
        assertThat(Duration.between(
                        claims.getIssueTime().toInstant(),
                        claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void returnsExpiryMatchingToken() {
        assertThat(issuer().issue(accountId, AccountType.ADMIN, null).expiresAt())
                .isEqualTo(NOW.plus(Duration.ofHours(2)));
    }

    @Test
    void adminWithoutSchoolOmitsSchoolClaim() throws Exception {
        String token = issuer().issue(accountId, AccountType.ADMIN, null).token();

        assertThat(parse(token).getClaim("school_id")).isNull();
    }

    @Test
    void everyIssueGetsUniqueJti() throws Exception {
        JwtTokenIssuer issuer = issuer();
        String first = issuer.issue(accountId, AccountType.STUDENT, schoolId).token();
        String second = issuer.issue(accountId, AccountType.STUDENT, schoolId).token();

        assertThat(parse(first).getJWTID()).isNotEqualTo(parse(second).getJWTID());
    }

    @Test
    void tokenIsNotVerifiableWithAnotherSecret() throws Exception {
        String token = issuer().issue(accountId, AccountType.STUDENT, schoolId).token();

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new MACVerifier(
                        "another-secret-32-bytes-minimum-0000000".getBytes(StandardCharsets.UTF_8))))
                .isFalse();
    }

    @Test
    void rejectsSecretShorterThanHs256Minimum() {
        assertThatThrownBy(() -> new JwtTokenIssuer("too-short", Duration.ofHours(2), FIXED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankSecret() {
        assertThatThrownBy(() -> new JwtTokenIssuer("", Duration.ofHours(2), FIXED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
