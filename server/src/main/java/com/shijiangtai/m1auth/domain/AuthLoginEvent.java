package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 登录审计（M1 §3.1 / 12 §3.1 auth_login_events）。
 *
 * <p>只记录方式、provider、成功 / 失败、IP、UA；不得记录 token、验证码、微信 access token（FR-M1-08、03 §3.10）。
 */
public record AuthLoginEvent(
        UUID id,
        AccountId accountId,
        LoginMethod method,
        String provider,
        boolean success,
        String ip,
        String userAgent,
        Instant createdAt) {

    /** {@code ck_login_method}：password / email / wechat。 */
    public enum LoginMethod {
        PASSWORD,
        EMAIL,
        WECHAT;

        public String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** {@code user_agent varchar(500)}。 */
    private static final int MAX_USER_AGENT = 500;

    public AuthLoginEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(createdAt, "createdAt");
        userAgent = truncate(userAgent);
    }

    /** 失败事件没有 accountId（账号可能不存在），用于「不泄露账号存在性」。 */
    public static AuthLoginEvent failure(
            UUID id, LoginMethod method, String provider, String ip, String userAgent, Instant now) {
        return new AuthLoginEvent(id, null, method, provider, false, ip, userAgent, now);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_USER_AGENT ? value : value.substring(0, MAX_USER_AGENT);
    }
}
