package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 认证审计（M1 §3.1 / 12 §3.1 auth_login_events）。
 *
 * <p>记录事件类型、方式、provider、成功 / 失败、IP、UA；不得记录 token、验证码、微信 access token（FR-M1-08、03 §3.10）。
 */
public record AuthLoginEvent(
        UUID id,
        AccountId accountId,
        EventType eventType,
        LoginMethod method,
        String provider,
        boolean success,
        String ip,
        String userAgent,
        Instant createdAt) {

    /** {@code ck_event_type}：login / bind / unbind（V15）。 */
    public enum EventType {
        LOGIN,
        BIND,
        UNBIND;

        public String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

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
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(createdAt, "createdAt");
        userAgent = truncate(userAgent);
    }

    /**
     * 登录失败且账号无法定位（用户名不存在 / 邮箱未绑定 / ticket 无效）：account_id 留空。
     *
     * <p>对外仍是统一 401，不泄露账号是否存在；此处留空仅因确实无从归属。
     */
    public static AuthLoginEvent failure(
            UUID id, LoginMethod method, String provider, String ip, String userAgent, Instant now) {
        return new AuthLoginEvent(
                id, null, EventType.LOGIN, method, provider, false, ip, userAgent, now);
    }

    /**
     * 登录失败但账号已定位（密码错误、验证码错误等）：必须归属账号。
     *
     * <p>否则 {@code /auth/security/events} 按 account_id 过滤时，本人看不到自己的失败
     * 登录尝试，账号安全页就失去「发现异常登录」的作用。该归属只在内部审计表，不参与
     * 任何对外响应，故不影响 03 §3.10「不泄露账号存在性」。
     */
    public static AuthLoginEvent loginFailure(
            UUID id, AccountId accountId, LoginMethod method, String provider,
            String ip, String userAgent, Instant now) {
        Objects.requireNonNull(accountId, "accountId");
        return new AuthLoginEvent(
                id, accountId, EventType.LOGIN, method, provider, false, ip, userAgent, now);
    }

    /** 绑定审计：操作者已登录，accountId 必填；成功 / 失败都要记录。 */
    public static AuthLoginEvent bind(
            UUID id, AccountId accountId, LoginMethod method, String provider,
            boolean success, String ip, String userAgent, Instant now) {
        return accountEvent(
                id, accountId, EventType.BIND, method, provider, success, ip, userAgent, now);
    }

    /** 解绑审计：操作者已登录，accountId 必填；成功 / 失败都要记录。 */
    public static AuthLoginEvent unbind(
            UUID id, AccountId accountId, LoginMethod method, String provider,
            boolean success, String ip, String userAgent, Instant now) {
        return accountEvent(
                id, accountId, EventType.UNBIND, method, provider, success, ip, userAgent, now);
    }

    private static AuthLoginEvent accountEvent(
            UUID id, AccountId accountId, EventType eventType, LoginMethod method,
            String provider, boolean success, String ip, String userAgent, Instant now) {
        Objects.requireNonNull(accountId, "accountId");
        return new AuthLoginEvent(
                id, accountId, eventType, method, provider, success, ip, userAgent, now);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_USER_AGENT ? value : value.substring(0, MAX_USER_AGENT);
    }
}
