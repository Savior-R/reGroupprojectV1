package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 登录方式端口：微信身份、邮箱验证码、登录审计（12 §3.1 三张新表）。
 */
public interface AuthMethodRepository {

    // ------------------------------------------------------ oauth_identities
    Optional<OAuthIdentity> findIdentity(String provider, String providerUserId);

    Optional<OAuthIdentity> findIdentityByAccount(AccountId accountId, String provider);

    OAuthIdentity saveIdentity(OAuthIdentity identity);

    void deleteIdentity(AccountId accountId, String provider);

    // --------------------------------------------------- email_login_codes
    /** 该邮箱最近一条未消费验证码（新码应使旧码失效，故只保留一条可用）。 */
    Optional<EmailLoginCode> findLatestCode(String email, EmailLoginCode.Purpose purpose);

    /** 最近一次发码时间，用于 60 秒重发间隔（FR-M1-09）。 */
    Optional<Instant> findLastCodeCreatedAt(String email);

    long countCodesByEmailSince(String email, Instant since);

    long countCodesByIpSince(String ip, Instant since);

    EmailLoginCode insertCode(EmailLoginCode code);

    void updateCode(EmailLoginCode code);

    /** 使该邮箱全部未消费码立即失效（发新码时调用）。 */
    void invalidatePendingCodes(String email, Instant now);

    // --------------------------------------------------- auth_login_events
    void insertLoginEvent(AuthLoginEvent event);

    List<AuthLoginEvent> findRecentEvents(UUID accountId, int limit);
}
