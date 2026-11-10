package com.shijiangtai.m1auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 邮箱登录验证码（M1 §3.1 / 12 §3.1 email_login_codes）。
 *
 * <p>不变量：6 位、5 分钟、单次使用、最多 5 次尝试；只存 HMAC 哈希，明文不落库（FR-M1-09）。
 */
public record EmailLoginCode(
        UUID id,
        String email,
        String codeHash,
        Purpose purpose,
        Instant expiresAt,
        int attempts,
        Instant consumedAt,
        String requestIp,
        Instant createdAt) {

    /** {@code ck_email_code_purpose}：login / bind。 */
    public enum Purpose {
        LOGIN,
        BIND;

        public String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public EmailLoginCode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(codeHash, "codeHash");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** 仍可用于一次校验：未消费、未过期、尝试次数未达上限。 */
    public boolean isUsable(Instant now, int maxAttempts) {
        return !isConsumed() && !isExpired(now) && attempts < maxAttempts;
    }

    /** 常量时间比较，避免通过响应时间侧信道猜码。 */
    public boolean matchesHash(String candidateHash) {
        if (candidateHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                codeHash.getBytes(StandardCharsets.UTF_8),
                candidateHash.getBytes(StandardCharsets.UTF_8));
    }

    /** 记录一次失败尝试。 */
    public EmailLoginCode withFailedAttempt() {
        return new EmailLoginCode(
                id, email, codeHash, purpose, expiresAt, attempts + 1, consumedAt, requestIp, createdAt);
    }

    /** 校验成功后标记消费，禁止重放。 */
    public EmailLoginCode consume(Instant now) {
        return new EmailLoginCode(
                id, email, codeHash, purpose, expiresAt, attempts, now, requestIp, createdAt);
    }
}
