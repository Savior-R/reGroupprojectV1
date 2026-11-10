package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 第三方身份（M1 §3.1 / 12 §3.1 oauth_identities）。
 *
 * <ul>
 *   <li>唯一键：{@code (provider, provider_user_id)}、{@code (account_id, provider)}
 *   <li>只存稳定 ID，不存 access token / secret（FR-M1-10）
 *   <li>昵称 / 头像仅用于展示，不作登录标识
 * </ul>
 */
public record OAuthIdentity(
        UUID id,
        AccountId accountId,
        String provider,
        String providerUserId,
        String unionId,
        String email,
        String nickname,
        String avatarUrl,
        Instant createdAt,
        Instant lastLoginAt) {

    /** P0 仅接微信开放平台（02 §4.1）。 */
    public static final String PROVIDER_WECHAT = "wechat";

    public OAuthIdentity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerUserId, "providerUserId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static OAuthIdentity wechat(
            UUID id,
            AccountId accountId,
            String openId,
            String unionId,
            String email,
            String nickname,
            String avatarUrl,
            Instant now) {
        return new OAuthIdentity(
                id, accountId, PROVIDER_WECHAT, openId, unionId, email, nickname, avatarUrl, now, now);
    }

    public OAuthIdentity touchLogin(Instant now) {
        return new OAuthIdentity(
                id, accountId, provider, providerUserId, unionId, email, nickname, avatarUrl,
                createdAt, now);
    }
}
